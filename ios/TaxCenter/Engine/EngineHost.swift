import Foundation
import UIKit
import WebKit

/// Το αποτέλεσμα μιας εκτέλεσης, όπως το επιστρέφει το `runner.js`.
struct EngineRunResult {
    var ok: Bool
    var reason: String
    var files: [String]
    /// Ό,τι επέστρεψε το config στο πεδίο `out`, ως JSON.
    var out: String?
    var durationMs: Int
    /// Οι γραμμές του log, ήδη περασμένες από τον `Redactor`.
    var log: [String]
}

/// Τρέχει μια διαδικασία του runner στη συσκευή.
///
/// Ο host είναι ένα **κρυφό `WKWebView` που δεν πλοηγείται ποτέ**: μένει σε
/// `about:blank` και εκτελεί μόνο τα δικά μας αρχεία του bundle. Είναι ο ίδιος
/// σχεδιασμός με το Android (`JsHost.kt`), και για τον ίδιο λόγο: ο engine
/// γράφτηκε για Node, και ένα WebView του δίνει `URL`, `TextDecoder`,
/// `setTimeout` και σύγχρονη JavaScript χωρίς να πειραχτεί ούτε γραμμή του.
///
/// Όλο το δίκτυο περνά από το [HttpTransport] — ποτέ από το ίδιο το WebView.
@MainActor
final class EngineHost {

    nonisolated static let defaultTimeout: TimeInterval = 10 * 60

    private let assets: EngineAssets
    private let transport: HttpTransport

    init(assets: EngineAssets = .shared, transport: HttpTransport = URLSessionTransport()) {
        self.assets = assets
        self.transport = transport
    }

    /// Εκτελεί το config και γράφει τα αρχεία του στο `outDir`.
    ///
    /// Δεν πετάει για αποτυχία της διαδικασίας: επιστρέφει `ok == false` με
    /// λόγο, όπως ο runner, ώστε μια σειρά λήψεων να συνεχίζει στην επόμενη.
    ///
    /// - Parameter secrets: τιμές που δεν πρέπει να φανούν ποτέ στο log.
    func run(
        configId: String,
        inputs: [String: String],
        outDir: URL,
        timeout: TimeInterval = EngineHost.defaultTimeout,
        keepDiagnostics: Bool = false,
        secrets: [String] = []
    ) async -> EngineRunResult {
        let session = EngineSession(
            assets: assets,
            transport: transport,
            files: FileBridge(root: outDir, keepDiagnostics: keepDiagnostics),
            configId: configId,
            secrets: secrets
        )
        return await session.run(inputs: inputs, timeout: timeout)
    }
}

/// Μία εκτέλεση: ένα WebView, μία γέφυρα, ένα αποτέλεσμα.
@MainActor
private final class EngineSession: NSObject, WKScriptMessageHandler, WKUIDelegate, WKNavigationDelegate {

    private let assets: EngineAssets
    private let transport: HttpTransport
    private let files: FileBridge
    private let configId: String
    private let secrets: [String]

    private var webView: WKWebView?
    private var log: [String] = []
    private var loaded: CheckedContinuation<Void, Never>?
    private var finished: CheckedContinuation<String, Never>?
    private var timeoutTask: Task<Void, Never>?

    init(assets: EngineAssets, transport: HttpTransport, files: FileBridge, configId: String, secrets: [String]) {
        self.assets = assets
        self.transport = transport
        self.files = files
        self.configId = configId
        self.secrets = secrets
    }

    func run(inputs: [String: String], timeout: TimeInterval) async -> EngineRunResult {
        guard assets.available else {
            return EngineRunResult(
                ok: false, reason: "Λείπει ο engine από την εφαρμογή.", files: [], out: nil, durationMs: 0, log: []
            )
        }

        let configuration = WKWebViewConfiguration()
        // Τίποτα δεν μένει στον δίσκο από τον host.
        configuration.websiteDataStore = .nonPersistent()
        configuration.userContentController.add(self, name: "bridge")

        let web = WKWebView(frame: CGRect(x: 0, y: 0, width: 1, height: 1), configuration: configuration)
        web.uiDelegate = self
        web.navigationDelegate = self
        web.alpha = 0.01
        web.isUserInteractionEnabled = false
        // Μέσα σε παράθυρο, αλλιώς το WebKit θεωρεί τη σελίδα αόρατη και
        // καθυστερεί τα χρονόμετρά της.
        Self.hostWindow()?.addSubview(web)
        webView = web
        defer { teardown() }

        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            loaded = continuation
            web.loadHTMLString("<html><head></head><body></body></html>", baseURL: nil)
        }

        await evaluate(Self.bridgeScript)
        await evaluate(assets.shims)
        await evaluate(assets.runner)

        let payload = Self.json(inputs) ?? "{}"
        let json: String = await withCheckedContinuation { (continuation: CheckedContinuation<String, Never>) in
            finished = continuation
            call("__runConfig", ["run", configId, payload, "."])
            timeoutTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                if Task.isCancelled { return }
                let expired: [String: Any] = ["ok": false, "reason": "Λήξη χρόνου μετά από \(Int(timeout))s"]
                self?.finish(with: Self.json(expired) ?? "{}")
            }
        }
        return parse(json)
    }

    // ---------------------------------------------------------- JS -> native

    // Οι μέθοδοι των delegates έχουν ρητό `@objc`: οι υπογραφές τους απέκτησαν
    // annotations ταυτόχρονης εκτέλεσης σε νεότερα SDK, και μια μέθοδος που ο
    // compiler δεν θα αναγνώριζε ως υλοποίηση του πρωτοκόλλου απλώς δεν θα
    // καλούνταν ποτέ — χωρίς σφάλμα πουθενά.

    /// Το `__bridge` που βλέπει το shims.js.
    ///
    /// Οι ασύγχρονες κλήσεις (HTTP, τέλος, log) φεύγουν ως μηνύματα. Οι
    /// **σύγχρονες** (αρχεία, `require`) χρειάζονται τιμή επιστροφής επιτόπου,
    /// και το WKWebView έχει έναν μόνο σύγχρονο δρόμο προς το native: το
    /// `prompt()`, που μπλοκάρει τη JavaScript ώσπου να απαντήσει ο delegate.
    private static let bridgeScript = """
    (function () {
      function post(o) { window.webkit.messageHandlers.bridge.postMessage(o); }
      function sync(m, a) { return window.prompt('__bridge', JSON.stringify({ m: m, a: a })); }
      window.__bridge = {
        httpRequest: function (id, json) { post({ m: 'httpRequest', id: String(id), json: String(json) }); },
        pageCall: function (id, json) { post({ m: 'pageCall', id: String(id), json: String(json) }); },
        finish: function (id, json) { post({ m: 'finish', id: String(id), json: String(json) }); },
        log: function (line) { post({ m: 'log', line: String(line) }); return ''; },
        fileWrite: function (p, d, append) { return sync('fileWrite', [String(p), String(d), !!append]) || ''; },
        fileRead: function (p) { return sync('fileRead', [String(p)]); },
        fileExists: function (p) { return sync('fileExists', [String(p)]) || '0'; },
        fileSize: function (p) { return sync('fileSize', [String(p)]) || '-1'; },
        mkdirs: function (p) { return sync('mkdirs', [String(p)]) || ''; },
        moduleSource: function (name) { return sync('moduleSource', [String(name)]); }
      };
    })();
    """

    @objc func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let method = body["m"] as? String else { return }
        switch method {
        case "log":
            append(body["line"] as? String ?? "")
        case "finish":
            finish(with: body["json"] as? String ?? "{}")
        case "httpRequest":
            guard let id = body["id"] as? String, let json = body["json"] as? String else { return }
            let transport = self.transport
            Task { [weak self] in
                do {
                    let reply = try await transport.execute(json)
                    self?.call("__resolve", [id, reply])
                } catch {
                    // Ο engine περιμένει το σφάλμα δικτύου ως rejection του fetch.
                    self?.call("__reject", [id, error.localizedDescription])
                }
            }
        case "pageCall":
            guard let id = body["id"] as? String else { return }
            call("__reject", [id, "Η διαδικασία «\(configId)» χρειάζεται πραγματικό browser, που δεν υποστηρίζεται ακόμη στο iOS."])
        default:
            break
        }
    }

    @objc func webView(
        _ webView: WKWebView,
        runJavaScriptTextInputPanelWithPrompt prompt: String,
        defaultText: String?,
        initiatedByFrame frame: WKFrameInfo,
        completionHandler: @escaping (String?) -> Void
    ) {
        guard prompt == "__bridge",
              let data = defaultText?.data(using: .utf8),
              let request = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let method = request["m"] as? String
        else {
            completionHandler(nil)
            return
        }
        completionHandler(sync(method, request["a"] as? [Any] ?? []))
    }

    private func sync(_ method: String, _ args: [Any]) -> String? {
        func text(_ index: Int) -> String { args.indices.contains(index) ? (args[index] as? String ?? "") : "" }
        switch method {
        case "fileWrite":
            let append = args.indices.contains(2) ? (args[2] as? Bool ?? false) : false
            return files.write(text(0), base64: text(1), append: append)
        case "fileRead":
            return files.read(text(0))
        case "fileExists":
            return files.exists(text(0)) ? "1" : "0"
        case "fileSize":
            return String(files.size(text(0)))
        case "mkdirs":
            return files.mkdirs(text(0))
        case "moduleSource":
            return assets.moduleSource(text(0))
        default:
            return nil
        }
    }

    // ------------------------------------------------------------ πλοήγηση

    @objc func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        didLoad()
    }

    @objc func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        didLoad()
    }

    @objc func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        didLoad()
    }

    /// Ο host δεν φορτώνει ποτέ απομακρυσμένη σελίδα.
    @objc func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        let url = navigationAction.request.url?.absoluteString ?? "about:blank"
        decisionHandler(url == "about:blank" ? .allow : .cancel)
    }

    private func didLoad() {
        guard let continuation = loaded else { return }
        loaded = nil
        continuation.resume()
    }

    // ---------------------------------------------------------- native -> JS

    private func evaluate(_ source: String) async {
        guard let web = webView else { return }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            // Το «void 0» στο τέλος: αλλιώς το WebKit παραπονιέται όταν η
            // τελευταία έκφραση του αρχείου είναι συνάρτηση.
            web.evaluateJavaScript(source + "\n;void 0;") { [weak self] _, error in
                if let error { self?.append("ERROR engine: " + error.localizedDescription) }
                continuation.resume()
            }
        }
    }

    /// Καλεί μια global συνάρτηση με ορίσματα-συμβολοσειρές, ασφαλώς γραμμένα ως JSON.
    private func call(_ function: String, _ arguments: [String]) {
        guard let web = webView, let list = Self.json(arguments) else { return }
        web.evaluateJavaScript("\(function).apply(null, \(list));void 0;", completionHandler: nil)
    }

    private func finish(with json: String) {
        guard let continuation = finished else { return }
        finished = nil
        continuation.resume(returning: json)
    }

    private func append(_ line: String) {
        log.append(Redactor.scrub(line, secrets: secrets))
    }

    private func teardown() {
        timeoutTask?.cancel()
        timeoutTask = nil
        guard let web = webView else { return }
        web.configuration.userContentController.removeScriptMessageHandler(forName: "bridge")
        web.uiDelegate = nil
        web.navigationDelegate = nil
        web.stopLoading()
        web.removeFromSuperview()
        webView = nil
    }

    private func parse(_ json: String) -> EngineRunResult {
        guard let data = json.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else {
            return EngineRunResult(
                ok: false, reason: "Μη αναγνώσιμο αποτέλεσμα", files: [], out: nil, durationMs: 0, log: log
            )
        }
        var out: String?
        if let value = object["out"] as? [String: Any] { out = Self.json(value) }
        return EngineRunResult(
            ok: object["ok"] as? Bool ?? false,
            reason: Redactor.scrub(object["reason"] as? String ?? "", secrets: secrets),
            files: (object["files"] as? [Any] ?? []).compactMap { $0 as? String },
            out: out,
            durationMs: (object["durationMs"] as? NSNumber)?.intValue ?? 0,
            log: log
        )
    }

    private static func json(_ value: Any) -> String? {
        guard JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value)
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func hostWindow() -> UIWindow? {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
        return windows.first { $0.isKeyWindow } ?? windows.first
    }
}
