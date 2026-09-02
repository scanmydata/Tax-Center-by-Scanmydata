package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.data.db.SendEntity
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Ημερολόγιο αποστολών.
 *
 * Δείχνει, ανά μήνα ή εβδομάδα, τι στάλθηκε και σε ποιον. Οι **αποτυχημένες**
 * αποστολές εμφανίζονται κι αυτές, με κόκκινο: μια αποστολή που δεν έφτασε είναι
 * ακριβώς αυτό που πρέπει να δει ο λογιστής.
 *
 * Οι ώρες υπολογίζονται σε ζώνη Αθηνών, όχι UTC — το «τι έστειλα χθες» πρέπει να
 * σημαίνει χθες για τον χρήστη.
 */
private val ZONE: ZoneId = ZoneId.of("Europe/Athens")
private val GREEK = Locale("el", "GR")

@Composable
fun SendCalendarScreen(container: AppContainer, modifier: Modifier = Modifier) {
    var weekView by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(LocalDate.now(ZONE)) }
    var selected by remember { mutableStateOf<LocalDate?>(LocalDate.now(ZONE)) }

    val range = remember(anchor, weekView) { visibleRange(anchor, weekView) }
    val sends: List<SendEntity> by container.db.sends()
        .observeBetween(range.first.atStartOfDay(ZONE).toInstant().toEpochMilli(),
                        range.second.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli())
        .collectAsState(initial = emptyList())

    val byDay = remember(sends) { sends.groupBy { it.sentAt.toLocalDate() } }

    Column(modifier.padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                headerLabel(anchor, weekView),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row {
                IconButton(onClick = { anchor = if (weekView) anchor.minusWeeks(1) else anchor.minusMonths(1) }) {
                    Text("‹", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = { anchor = if (weekView) anchor.plusWeeks(1) else anchor.plusMonths(1) }) {
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !weekView, onClick = { weekView = false }, label = { Text("Μήνας") })
            FilterChip(selected = weekView, onClick = { weekView = true }, label = { Text("Εβδομάδα") })
        }

        Spacer(Modifier.height(12.dp))
        WeekdayHeader()
        CalendarGrid(
            days = calendarDays(anchor, weekView),
            currentMonth = YearMonth.from(anchor),
            byDay = byDay,
            selected = selected,
            onSelect = { selected = it },
        )

        Spacer(Modifier.height(12.dp))
        val listed = selected?.let { byDay[it].orEmpty() } ?: sends
        Text(
            if (selected != null) {
                "${listed.size} αποστολές — ${selected!!.format(dayFormatter)}"
            } else {
                "${listed.size} αποστολές"
            },
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(6.dp))
        LazyColumn { items(listed, key = { it.id }) { SendRow(it) } }
    }
}

// --------------------------------------------------------------------- πλέγμα

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth()) {
        // Η εβδομάδα ξεκινά Δευτέρα, όπως στην Ελλάδα.
        for (d in DayOfWeek.entries) {
            Text(
                d.getDisplayName(TextStyle.NARROW, GREEK),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun CalendarGrid(
    days: List<LocalDate>,
    currentMonth: YearMonth,
    byDay: Map<LocalDate, List<SendEntity>>,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
) {
    Column {
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    DayCell(
                        day = day,
                        inMonth = YearMonth.from(day) == currentMonth,
                        sends = byDay[day].orEmpty(),
                        isSelected = day == selected,
                        onClick = { onSelect(day) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    sends: List<SendEntity>,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasFailure = sends.any { it.failed }
    Box(
        modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (inMonth) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            )
            if (sends.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .size(if (sends.size > 3) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (hasFailure) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- μία γραμμή

@Composable
private fun SendRow(send: SendEntity) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(send.clientName, style = MaterialTheme.typography.titleSmall)
                Text(
                    send.sentAt.toLocalTimeLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Text(send.toEmail, style = MaterialTheme.typography.bodySmall)
            Text(
                buildString {
                    append(
                        when (send.kind) {
                            SendEntity.KIND_CREDENTIALS -> "Στοιχεία πελάτη"
                            else -> "Φορολογικά έντυπα"
                        },
                    )
                    if (send.itemCount > 0) append(" · ${send.itemCount}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            if (send.failed) {
                Text(
                    "Απέτυχε: ${send.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ βοηθητικά

private val dayFormatter = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", GREEK)
private val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm", GREEK)

private fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZONE).toLocalDate()

private fun Long.toLocalTimeLabel(): String =
    Instant.ofEpochMilli(this).atZone(ZONE).toLocalTime().format(timeFormatter)

private fun headerLabel(anchor: LocalDate, weekView: Boolean): String = if (weekView) {
    val start = anchor.with(DayOfWeek.MONDAY)
    "${start.dayOfMonth} – ${start.plusDays(6).format(dayFormatter)}"
} else {
    val month = YearMonth.from(anchor)
    "${month.month.getDisplayName(TextStyle.FULL_STANDALONE, GREEK)} ${month.year}"
}

/** Το διάστημα που ερωτάται η βάση — καλύπτει ό,τι φαίνεται στο πλέγμα. */
private fun visibleRange(anchor: LocalDate, weekView: Boolean): Pair<LocalDate, LocalDate> {
    val days = calendarDays(anchor, weekView)
    return days.first() to days.last()
}

/** Πάντα πλήρεις εβδομάδες Δευτέρα–Κυριακή, ώστε το πλέγμα να μη «χωλαίνει». */
private fun calendarDays(anchor: LocalDate, weekView: Boolean): List<LocalDate> {
    if (weekView) {
        val start = anchor.with(DayOfWeek.MONDAY)
        return (0L..6L).map(start::plusDays)
    }
    val month = YearMonth.from(anchor)
    val first = month.atDay(1).with(DayOfWeek.MONDAY)
    val last = month.atEndOfMonth().with(DayOfWeek.SUNDAY)
    val count = java.time.temporal.ChronoUnit.DAYS.between(first, last)
    return (0L..count).map(first::plusDays)
}
