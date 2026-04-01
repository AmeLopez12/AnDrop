package com.example.androp

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
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

    //variable para interactuar con el hardware NFC del telefono
    private var nfcAdapter: NfcAdapter? = null
    // permite capturar el evento NFC antes que otras apps
    private var pendingIntent: PendingIntent? = null
    //Estados para los mensajes
    private var messageToSend by mutableStateOf("")
    private var receivedMessage by mutableStateOf("")
    private var isSendMode by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        //inicia el NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        //se verifica que el telefono tenga NFC
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC no disponible", Toast.LENGTH_LONG).show()
        }

        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )

        //configurar el callback para cuando el mensaje sea enviado (leído por el receptor)
        MyHostApduService.onMessageSent = {
            runOnUiThread {
                if (messageToSend.isNotEmpty()) {
                    messageToSend = ""
                    MyHostApduService.messageToShare = ""
                    Toast.makeText(this, "¡Mensaje enviado con éxito!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        setContent {
            AnDropTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NfcScreen(
                        modifier = Modifier.padding(innerPadding),
                        messageToSend = messageToSend,
                        onMessageChange = { 
                            messageToSend = it
                            //actualizamos el mensaje en el servicio de emulacion
                            MyHostApduService.messageToShare = it
                        },
                        receivedMessage = receivedMessage,
                        isSendMode = isSendMode,
                        onModeChange = { 
                            isSendMode = it 
                            if (!it) receivedMessage = ""
                        },
                        onSendClick = { 
                            if (messageToSend.isBlank()) {
                                Toast.makeText(this, "Escribe un mensaje primero", Toast.LENGTH_SHORT).show()
                            } else {
                                MyHostApduService.messageToShare = messageToSend
                                Toast.makeText(this, "Modo Enviar activo. Acerca el otro teléfono.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        //capturamos cualquier evento NFC
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        //desactiva prioridad al salir de la app para no interferir con otros procesos
        nfcAdapter?.disableForegroundDispatch(this)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        //si estamos en modo recibir, intentamos leer al otro telefono que estara emulando una tarjeta
        if (!isSendMode) {
            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            tag?.let { readFromEmulatedTag(it) }
        }
    }

    private fun readFromEmulatedTag(tag: Tag) {
        val isoDep = IsoDep.get(tag) ?: return
        //comando para seleccionar servicio (AID: F0010203040506)
        val selectCommand = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), 0x07.toByte(),
            0xF0.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte(), 0x05.toByte(), 0x06.toByte()
        )

        try {
            isoDep.connect()
            //tiempo de espera para asegurar la lectura
            isoDep.timeout = 5000
            
            //envia comando de seleccion al otro telefono
            val response = isoDep.transceive(selectCommand)
            
            //verifica si la respuesta termina en 0x90 0x00 (Significa que es exito)
            if (response.size >= 2 && response[response.size - 2] == 0x90.toByte() && response[response.size - 1] == 0x00.toByte()) {
                val payload = response.copyOfRange(0, response.size - 2)
                val text = String(payload, Charset.forName("UTF-8"))
                
                runOnUiThread {
                    receivedMessage = text
                    Toast.makeText(this, "¡Mensaje recibido!", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                // Toast.makeText(this, "Error al recibir: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            try {
                isoDep.close()
            } catch (_: Exception) {}
        }
    }
}

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
            OutlinedTextField(
                value = messageToSend,
                onValueChange = onMessageChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .border(1.dp, Color.Black, RoundedCornerShape(20.dp)),
                placeholder = {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Mensaje a Enviar", textAlign = TextAlign.Center, color = Color.Gray, fontSize = 24.sp)
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(cardBackground, RoundedCornerShape(20.dp))
                    .border(1.dp, Color.Black, RoundedCornerShape(20.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.TopStart
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
                        text = if (receivedMessage.isEmpty()) "Esperando..." else receivedMessage,
                        fontSize = 18.sp,
                        color = Color.Black
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

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
