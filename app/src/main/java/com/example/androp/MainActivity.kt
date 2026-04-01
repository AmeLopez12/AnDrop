package com.example.androp

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androp.ui.theme.AnDropTheme
import java.nio.charset.Charset

class MainActivity : ComponentActivity() {

    //Adaptador NFC para interactuar con el hardware del telefono
    private var nfcAdapter: NfcAdapter? = null
    //Intencion pendiente para manejar eventos NFC en primer plano
    private var pendingIntent: PendingIntent? = null
    //Filtros para especificar que tipo de mensajes NFC queremos recibir
    private var intentFiltersArray: Array<IntentFilter>? = null
    //Estado para el mensaje a enviar y el mensaje recibido
    private var messageToSend by mutableStateOf("")
    private var receivedMessage by mutableStateOf("")
    private var isSendMode by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        //Inicializar el NfcAdapter
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        //Verificar si el dispositivo tiene NFC
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC no esta disponible en este dispositivo", Toast.LENGTH_LONG).show()
        } else if (!nfcAdapter!!.isEnabled) {
            Toast.makeText(this, "NFC esta desactivado. Por favor, activalo en los ajustes.", Toast.LENGTH_LONG).show()
        }
        //Preparar el PendingIntent para recibir intents NFC
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        //Configurar filtro para detectar mensajes NDEF de texto plano
        val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try {
                addDataType("text/plain")
            } catch (e: IntentFilter.MalformedMimeTypeException) {
                throw RuntimeException("Fallo al añadir tipo MIME", e)
            }
        }
        intentFiltersArray = arrayOf(ndefFilter)
        //Definicion de la interfaz de usuario con Compose
        setContent {
            AnDropTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NfcScreen(
                        modifier = Modifier.padding(innerPadding),
                        messageToSend = messageToSend,
                        onMessageChange = { messageToSend = it },
                        receivedMessage = receivedMessage,
                        isSendMode = isSendMode,
                        onModeChange = { isSendMode = it },
                        onSendClick = { prepareNfcMessage() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        //Habilitar la prioridad de la app para capturar señales NFC
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFiltersArray, null)
    }

    override fun onPause() {
        super.onPause()
        //Deshabilitar la prioridad al pausar la actividad
        nfcAdapter?.disableForegroundDispatch(this)
    }

    //Metodo que se ejecuta cuando se detecta una etiqueta o dispositivo NFC
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        //Manejar recepcion de datos
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action) {
            val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            if (rawMsgs != null) {
                val msgs = arrayOfNulls<NdefMessage>(rawMsgs.size)
                for (i in rawMsgs.indices) {
                    msgs[i] = rawMsgs[i] as NdefMessage
                }
                displayReceivedData(msgs[0])
            }
        } else if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action && isSendMode) {
            //Manejar envio de datos (Escritura)
            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            tag?.let { writeNdefMessage(it) }
        }
    }

    //Avisar al usuario que debe acercar el dispositivo
    private fun prepareNfcMessage() {
        if (messageToSend.isBlank()) {
            Toast.makeText(this, "Escribe un mensaje primero", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Acerca el otro dispositivo para enviar", Toast.LENGTH_SHORT).show()
    }

    //Logica para grabar el mensaje en el dispositivo detectado
    private fun writeNdefMessage(tag: Tag) {
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            Toast.makeText(this, "Este tag no soporta NDEF", Toast.LENGTH_SHORT).show()
            return
        }
        //Crear registro NDEF con el texto
        val record = NdefRecord.createTextRecord("es", messageToSend)
        val message = NdefMessage(arrayOf(record))
        try {
            ndef.connect()
            if (!ndef.isWritable) {
                Toast.makeText(this, "El tag es de solo lectura", Toast.LENGTH_SHORT).show()
                return
            }
            ndef.writeNdefMessage(message)
            Toast.makeText(this, "Mensaje enviado correctamente", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al enviar: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            ndef.close()
        }
    }
    //Decodificar el mensaje NDEF recibido
    private fun displayReceivedData(ndefMessage: NdefMessage?) {
        val record = ndefMessage?.records?.get(0)
        val payload = record?.payload
        if (payload != null) {
            val languageCodeLength = payload[0].toInt() and 0x3F
            val textEncoding = if ((payload[0].toInt() and 0x80) == 0) "UTF-8" else "UTF-16"
            val text = String(payload, languageCodeLength + 1, payload.size - languageCodeLength - 1, Charset.forName(textEncoding))
            receivedMessage = text
        }
    }
}

//Interfaz de usuario principal
@Composable
fun NfcScreen(
    modifier: Modifier = Modifier,
    messageToSend: String,
    onMessageChange: (String) -> Unit,
    receivedMessage: String,
    isSendMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    onSendClick: () -> Unit
) {
    val mainBlue = Color(0xFF007BFF)
    val lightBlue = Color(0xFF8ECAFF)
    val cardBackground = Color(0xFFD6E9FF)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(60.dp))

        //Titulo de la app
        Text(
            text = "ANDROP",
            style = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.Bold,
                color = mainBlue,
                fontSize = 60.sp
            )
        )
        Text(
            text = "Con NFC",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Normal,
                color = Color.Black
            )
        )
        Spacer(modifier = Modifier.height(30.dp))
        //Botones Enviar / Recibir
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            ModeButton(
                text = "Enviar",
                isSelected = isSendMode,
                onClick = { onModeChange(true) },
                selectedColor = lightBlue
            )
            Spacer(modifier = Modifier.width(30.dp))
            ModeButton(
                text = "Recibir",
                isSelected = !isSendMode,
                onClick = { onModeChange(false) },
                selectedColor = lightBlue
            )
        }
        Spacer(modifier = Modifier.height(40.dp))
        if (isSendMode) {
            //Cuadro para escribir mensaje
            OutlinedTextField(
                value = messageToSend,
                onValueChange = onMessageChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .border(1.dp, Color.Black, RoundedCornerShape(20.dp)),
                placeholder = {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Mensaje a Enviar", textAlign = TextAlign.Center, color = Color.Black, fontSize = 24.sp)
                    }
                },
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent
                ),
                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center, fontSize = 20.sp)
            )
            Spacer(modifier = Modifier.weight(1f))
            //Boton para enviar
            Button(
                onClick = onSendClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = mainBlue),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(text = "Enviar Mensaje", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(60.dp))

        } else {
            //Cuadro de informacion recibida
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(cardBackground, RoundedCornerShape(20.dp))
                    .border(1.dp, Color.Black, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = "Información recibida:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = receivedMessage,
                        fontSize = 18.sp,
                        color = Color.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
            //Indicador de carga
            CircularProgressIndicator(
                modifier = Modifier.size(60.dp),
                color = Color.Gray,
                strokeWidth = 4.dp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Esperando señal NFC",
                fontSize = 20.sp,
                color = Color.Black
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

//Boton personalizado para cambiar de modo
@Composable
fun ModeButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    selectedColor: Color
) {
    Box(
        modifier = Modifier
            .width(130.dp)
            .height(45.dp)
            .background(
                if (isSelected) selectedColor else Color.White,
                RoundedCornerShape(22.dp)
            )
            .border(1.dp, Color.Black, RoundedCornerShape(22.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = Color.Black
        )
    }
}
