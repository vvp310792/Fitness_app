package com.fitnessapp.summary.ui.components

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.time.LocalDate
import java.time.ZoneId

/** Loading is not an empty measurement. Reset immediately when the query changes. */
data class ScreenRead<T>(val value:T?=null,val loaded:Boolean=false,val failed:Boolean=false)

@Composable
fun <T> readScreen(flow:Flow<T>,retry:Int=0):ScreenRead<T> {
    val state=remember(flow,retry){mutableStateOf(ScreenRead<T>())}
    LaunchedEffect(flow,retry) {
        try {
            flow.collect { state.value=ScreenRead(it,loaded=true) }
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { state.value=state.value.copy(failed=true) }
    }
    return state.value
}

@Composable
fun ReadStatus(reads:List<ScreenRead<*>>,onRetry:()->Unit,onSettings:(()->Unit)?=null) {
    val failed=reads.any{it.failed}
    val loading=reads.any{!it.loaded && !it.failed}
    if(!loading && !failed)return
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Загружаем измерения… Полученные данные доступны ниже.",style=MaterialTheme.typography.bodySmall)
        }
        if(failed) {
            Text("Не все данные удалось прочитать. Полученные измерения сохранены.",
                style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error)
            Row {
                TextButton(onClick=onRetry){Text("Повторить")}
                if(onSettings!=null)TextButton(onClick=onSettings){Text("Источники")}
            }
        }
    }
}

val LocalDateSaver=Saver<LocalDate,Long>(save={it.toEpochDay()},restore={LocalDate.ofEpochDay(it)})

@Composable
fun rememberCurrentDate():LocalDate {
    var date by remember {mutableStateOf(LocalDate.now())}
    LaunchedEffect(Unit) { while(true) { date=LocalDate.now();delay(60_000) } }
    return date
}

fun pickDate(context:Context,selected:LocalDate,latest:LocalDate,onPicked:(LocalDate)->Unit) {
    val date=selected.coerceAtMost(latest)
    val dialog=DatePickerDialog(context,{_,y,m,d->
        onPicked(LocalDate.of(y,m+1,d).coerceAtMost(latest))
    },date.year,date.monthValue-1,date.dayOfMonth)
    dialog.datePicker.maxDate=latest.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()-1L
    dialog.show()
}

@Composable
fun DateNavigator(title:String,subtitle:String?=null,canGoForward:Boolean,previousLabel:String,nextLabel:String,
                  currentLabel:String,selected:LocalDate,today:LocalDate,onPrevious:()->Unit,onNext:()->Unit,
                  onPick:(LocalDate)->Unit,onCurrent:()->Unit,atCurrent:Boolean) {
    val context=LocalContext.current
    Column {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onPrevious){Icon(Icons.Filled.ChevronLeft,contentDescription=previousLabel)}
            Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                Text(title,style=MaterialTheme.typography.titleLarge,textAlign=TextAlign.Center)
                subtitle?.let{Text(it,style=MaterialTheme.typography.bodySmall,textAlign=TextAlign.Center,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            IconButton(onClick=onNext,enabled=canGoForward){Icon(Icons.Filled.ChevronRight,contentDescription=nextLabel)}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
            TextButton(onClick={pickDate(context,selected,today,onPick)}){Text("Календарь")}
            if(!atCurrent)TextButton(onClick=onCurrent){Text(currentLabel)}
        }
    }
}

@Composable
fun ExpandableSection(title:String,summary:String,initiallyOpen:Boolean=false,content:@Composable ()->Unit) {
    var expanded by rememberSaveable {mutableStateOf(initiallyOpen)}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedCard(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium)
                Text(summary,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if(expanded)"Свернуть" else "Открыть",style=MaterialTheme.typography.labelLarge,
                    color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(top=6.dp))
            }
        }
        if(expanded)content()
    }
}

@Composable
fun ConfirmAction(label:String,title:String,message:String,confirmLabel:String="Удалить",onConfirm:()->Unit) {
    var opened by rememberSaveable {mutableStateOf(false)}
    TextButton(onClick={opened=true}){Text(label,color=MaterialTheme.colorScheme.error)}
    if(opened)AlertDialog(onDismissRequest={opened=false},title={Text(title)},text={Text(message)},
        confirmButton={TextButton(onClick={opened=false;onConfirm()}){Text(confirmLabel,color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton(onClick={opened=false}){Text("Отмена")}})
}
