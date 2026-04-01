package com.example.androp

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import java.nio.charset.Charset

class MyHostApduService : HostApduService() {

    companion object {
        //variable para guardar el mensaje
        var messageToShare: String = ""
        //callback para avisar a la Activity que el mensaje fue solicitado por el receptor
        var onMessageSent: (() -> Unit)? = null
    }

    //comando "SELECT AID" que el otro telefono enviara para empezar la comunicacion, debe coincidir con el AID en apduservice.xml (F0010203040506)
    private val SELECT_APDU = byteArrayOf(
        0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), 0x07.toByte(),
        0xF0.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte(), 0x05.toByte(), 0x06.toByte()
    )

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        //si se recibe el comando de seleccion, responde con el mensaje
        if (commandApdu != null && commandApdu.contentEquals(SELECT_APDU)) {
            val payload = messageToShare.toByteArray(Charset.forName("UTF-8"))
            
            //Avisa que el mensaje ha sido enviado (leido por el otro dispositivo)
            onMessageSent?.invoke()

            //Añade 0x90 0x00 al final (codigo de EXITO)
            val response = ByteArray(payload.size + 2)
            System.arraycopy(payload, 0, response, 0, payload.size)
            response[response.size - 2] = 0x90.toByte()
            response[response.size - 1] = 0x00.toByte()
            return response
        }
        
        //otro comando, entonces error (0x6F 0x00)
        return byteArrayOf(0x6F.toByte(), 0x00.toByte())
    }

    override fun onDeactivated(reason: Int) {
        //se llama cuando se rompe el contacto fisico
    }
}
