package com.papi.nova.binding.input.evdev

import android.app.Activity
import android.os.Build
import android.os.Looper
import android.widget.Toast
import com.papi.nova.LimeLog
import com.papi.nova.binding.input.capture.InputCaptureProvider
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class EvdevCaptureProvider(
    private val activity: Activity,
    private val listener: EvdevListener,
) : InputCaptureProvider() {
    private val libraryPath: String = activity.applicationInfo.nativeLibraryDir

    private var shutdown = false
    private var evdevIn: InputStream? = null
    private var evdevOut: OutputStream? = null
    private var su: Process? = null
    private var servSock: ServerSocket? = null
    private var evdevSock: Socket? = null
    private var started = false

    private val handlerThread = object : Thread() {
        override fun run() {
            var deltaX = 0
            var deltaY = 0
            var deltaVScroll: Byte = 0
            var deltaHScroll: Byte = 0

            // Bind a local listening socket for evdevreader to connect to
            val serverSocket = try {
                ServerSocket(0, 1).also { servSock = it }
            } catch (e: IOException) {
                e.printStackTrace()
                return
            }

            val evdevReaderCmd = libraryPath +
                File.separatorChar +
                "libevdev_reader.so " +
                serverSocket.localPort

            // On Nougat and later, we'll need to pass the command directly to SU.
            // Writing to SU's input stream after it has started doesn't seem to work anymore.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Launch evdev_reader directly via SU
                try {
                    su = ProcessBuilder("su", "-c", evdevReaderCmd).start()
                } catch (e: IOException) {
                    reportDeviceNotRooted()
                    e.printStackTrace()
                    return
                }
            } else {
                // Launch a SU shell on Marshmallow and earlier
                val builder = ProcessBuilder("su")
                builder.redirectErrorStream(true)

                try {
                    su = builder.start()
                } catch (e: IOException) {
                    reportDeviceNotRooted()
                    e.printStackTrace()
                    return
                }

                // Start evdevreader
                val suOut = DataOutputStream(su?.outputStream)
                try {
                    suOut.writeChars("$evdevReaderCmd\n")
                } catch (e: IOException) {
                    reportDeviceNotRooted()
                    e.printStackTrace()
                    return
                }
            }

            // Wait for evdevreader's connection
            LimeLog.info("Waiting for EvdevReader connection to port " + serverSocket.localPort)
            try {
                evdevSock = serverSocket.accept()
                evdevIn = evdevSock?.getInputStream()
                evdevOut = evdevSock?.getOutputStream()
            } catch (e: IOException) {
                e.printStackTrace()
                return
            }
            LimeLog.info("EvdevReader connected from port " + evdevSock?.port)

            while (!isInterrupted && !shutdown) {
                val event = try {
                    val input = evdevIn ?: break
                    EvdevReader.read(input)
                } catch (e: IOException) {
                    null
                } ?: break

                // Note: The EvdevReader process already filters input events when grabbing
                // is not enabled, so we don't need to that here.

                when (event.type) {
                    EvdevEvent.EV_SYN -> {
                        if (deltaX != 0 || deltaY != 0) {
                            listener.mouseMove(deltaX, deltaY)
                            deltaX = 0
                            deltaY = 0
                        }
                        if (deltaVScroll.toInt() != 0) {
                            listener.mouseVScroll(deltaVScroll)
                            deltaVScroll = 0
                        }
                        if (deltaHScroll.toInt() != 0) {
                            listener.mouseHScroll(deltaHScroll)
                            deltaHScroll = 0
                        }
                    }

                    EvdevEvent.EV_REL -> {
                        when (event.code) {
                            EvdevEvent.REL_X -> deltaX = event.value
                            EvdevEvent.REL_Y -> deltaY = event.value
                            EvdevEvent.REL_HWHEEL -> deltaHScroll = event.value.toByte()
                            EvdevEvent.REL_WHEEL -> deltaVScroll = event.value.toByte()
                        }
                    }

                    EvdevEvent.EV_KEY -> {
                        when (event.code) {
                            EvdevEvent.BTN_LEFT -> {
                                listener.mouseButtonEvent(
                                    EvdevListener.BUTTON_LEFT,
                                    event.value != 0,
                                )
                            }

                            EvdevEvent.BTN_MIDDLE -> {
                                listener.mouseButtonEvent(
                                    EvdevListener.BUTTON_MIDDLE,
                                    event.value != 0,
                                )
                            }

                            EvdevEvent.BTN_RIGHT -> {
                                listener.mouseButtonEvent(
                                    EvdevListener.BUTTON_RIGHT,
                                    event.value != 0,
                                )
                            }

                            EvdevEvent.BTN_SIDE -> {
                                listener.mouseButtonEvent(
                                    EvdevListener.BUTTON_X1,
                                    event.value != 0,
                                )
                            }

                            EvdevEvent.BTN_EXTRA -> {
                                listener.mouseButtonEvent(
                                    EvdevListener.BUTTON_X2,
                                    event.value != 0,
                                )
                            }

                            EvdevEvent.BTN_FORWARD,
                            EvdevEvent.BTN_BACK,
                            EvdevEvent.BTN_TASK,
                            -> {
                                // Other unhandled mouse buttons
                            }

                            else -> {
                                // We got some unrecognized button. This means
                                // someone is trying to use the other device in this
                                // "combination" input device. We'll try to handle
                                // it via keyboard, but we're not going to disconnect
                                // if we can't
                                val keyCode = EvdevTranslator.translateEvdevKeyCode(event.code)
                                if (keyCode.toInt() != 0) {
                                    listener.keyboardEvent(event.value != 0, keyCode)
                                }
                            }
                        }
                    }

                    EvdevEvent.EV_MSC -> {
                    }
                }
            }
        }
    }

    private fun reportDeviceNotRooted() {
        activity.runOnUiThread {
            Toast.makeText(
                activity,
                "This device is not rooted - Mouse capture is unavailable",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun runInNetworkSafeContextSynchronously(runnable: Runnable) {
        // This function is used to avoid Android's strict NetworkOnMainThreadException.
        // For our usage, it is highly unlikely to cause problems since we only do
        // write operations and only to localhost sockets.
        if (Looper.getMainLooper().thread == Thread.currentThread()) {
            val thread = Thread(runnable)
            thread.start()
            try {
                thread.join()
            } catch (e: InterruptedException) {
                // The main thread should never be interrupted
                e.printStackTrace()
            }
        } else {
            // Run the runnable directly
            runnable.run()
        }
    }

    override fun showCursor() {
        super.showCursor()
        // This may be called on the main thread
        runInNetworkSafeContextSynchronously {
            if (started && !shutdown && evdevOut != null) {
                try {
                    evdevOut?.write(UNGRAB_REQUEST)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun hideCursor() {
        super.hideCursor()
        // This may be called on the main thread
        runInNetworkSafeContextSynchronously {
            // Send a request to regrab if we're already capturing
            if (started && !shutdown && evdevOut != null) {
                try {
                    evdevOut?.write(REGRAB_REQUEST)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun enableCapture() {
        if (!started) {
            // Start the handler thread if it's our first time
            // capturing
            handlerThread.start()
            started = true
        }

        // Call the superclass only after we've started the handler thread.
        // It will invoke hideCursor() when we call it.
        super.enableCapture()
    }

    override fun destroy() {
        // We need to stop the process in this context otherwise
        // we could get stuck waiting on output from the process
        // in order to terminate it.
        //
        // This may be called on the main thread.

        if (!started) {
            return
        }

        shutdown = true
        handlerThread.interrupt()

        runInNetworkSafeContextSynchronously {
            if (servSock != null) {
                try {
                    servSock?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }

            if (evdevSock != null) {
                try {
                    evdevSock?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }

            if (evdevIn != null) {
                try {
                    evdevIn?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }

            if (evdevOut != null) {
                try {
                    evdevOut?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        su?.destroy()

        try {
            handlerThread.join()
        } catch (ignored: InterruptedException) {
        }
    }

    private companion object {
        private const val UNGRAB_REQUEST = 1
        private const val REGRAB_REQUEST = 2
    }
}
