package com.papi.nova.preferences

import android.app.AlertDialog
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.papi.nova.AppView
import com.papi.nova.Game
import com.papi.nova.PcView
import com.papi.nova.R
import com.papi.nova.ShortcutTrampoline
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.utils.Dialog
import com.papi.nova.utils.ServerHelper
import com.papi.nova.utils.SpinnerDialog
import com.papi.nova.utils.UiHelper
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue

class AddComputerManually : AppCompatActivity() {
    private lateinit var hostText: TextView
    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    private val computersToAdd = LinkedBlockingQueue<String>()
    private var addThread: Thread? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            managerBinder = binder as ComputerManagerService.ComputerManagerBinder
            startAddThread()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            joinAddThread()
            managerBinder = null
        }
    }

    private fun isWrongSubnetSiteLocalAddress(address: String): Boolean {
        try {
            val targetAddress = InetAddress.getByName(address)
            if (targetAddress !is Inet4Address || !targetAddress.isSiteLocalAddress) {
                return false
            }

            for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (addr in iface.interfaceAddresses) {
                    val ifaceAddress = addr.address
                    if (ifaceAddress !is Inet4Address || !ifaceAddress.isSiteLocalAddress) {
                        continue
                    }

                    val targetAddrBytes = targetAddress.address
                    val ifaceAddrBytes = ifaceAddress.address
                    var addressMatches = true
                    for (i in 0 until addr.networkPrefixLength) {
                        val ifaceBit = ifaceAddrBytes[i / 8].toInt() and (1 shl (i % 8))
                        val targetBit = targetAddrBytes[i / 8].toInt() and (1 shl (i % 8))
                        if (ifaceBit != targetBit) {
                            addressMatches = false
                            break
                        }
                    }

                    if (addressMatches) {
                        return false
                    }
                }
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun parseRawUserInputToUri(rawUserInput: String): Uri? {
        var uri = Uri.parse("art://$rawUserInput")
        if (!uri.host.isNullOrEmpty()) {
            return uri
        }

        uri = Uri.parse("art://[$rawUserInput]")
        if (!uri.host.isNullOrEmpty()) {
            return uri
        }

        return null
    }

    @Throws(InterruptedException::class)
    private fun doAddPc(rawUserInput: String) {
        var wrongSiteLocal = false
        var invalidInput = false
        var success: Boolean
        val dialog = SpinnerDialog.displayDialog(
            this,
            resources.getString(R.string.title_add_pc),
            resources.getString(R.string.msg_add_pc),
            false
        )

        val uri = parseRawUserInputToUri(rawUserInput)
        try {
            val details = ComputerDetails()
            if (uri?.host?.isNotEmpty() == true) {
                val host = uri.host!!
                var port = uri.port
                if (port == -1) {
                    port = NvHTTP.DEFAULT_HTTP_PORT
                }

                details.manualAddress = ComputerDetails.AddressTuple(host, port)
                success = managerBinder!!.addComputerBlocking(details)
                if (!success) {
                    wrongSiteLocal = isWrongSubnetSiteLocalAddress(host)
                }
            } else {
                success = false
                invalidInput = true
            }
        } catch (e: InterruptedException) {
            dialog.dismiss()
            throw e
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            success = false
            invalidInput = true
        }

        val portTestResult = if (!success && !wrongSiteLocal && !invalidInput) {
            MoonBridge.testClientConnectivity(
                ServerHelper.CONNECTION_TEST_SERVER,
                443,
                MoonBridge.ML_PORT_FLAG_TCP_47984 or MoonBridge.ML_PORT_FLAG_TCP_47989
            )
        } else {
            MoonBridge.ML_TEST_RESULT_INCONCLUSIVE
        }

        dialog.dismiss()

        if (invalidInput) {
            Dialog.displayDialog(
                this,
                resources.getString(R.string.conn_error_title),
                resources.getString(R.string.addpc_unknown_host),
                false
            )
        } else if (wrongSiteLocal) {
            Dialog.displayDialog(
                this,
                resources.getString(R.string.conn_error_title),
                resources.getString(R.string.addpc_wrong_sitelocal),
                false
            )
        } else if (!success) {
            val dialogText = if (
                portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE &&
                portTestResult != 0
            ) {
                resources.getString(R.string.nettest_text_blocked)
            } else {
                resources.getString(R.string.addpc_fail)
            }
            Dialog.displayDialog(this, resources.getString(R.string.conn_error_title), dialogText, false)
        } else {
            runOnUiThread {
                Toast.makeText(
                    this@AddComputerManually,
                    resources.getString(R.string.addpc_success),
                    Toast.LENGTH_LONG
                ).show()

                if (!isFinishing) {
                    finish()
                }

                val parsedUri = uri ?: return@runOnUiThread
                val pin = parsedUri.getQueryParameter("pin")
                val passphrase = parsedUri.getQueryParameter("passphrase")
                if (pin != null && passphrase != null) {
                    val intent = Intent(this@AddComputerManually, PcView::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                    intent.putExtra("hostname", parsedUri.host)
                    intent.putExtra("port", parsedUri.port)
                    intent.putExtra("pin", pin)
                    intent.putExtra("passphrase", passphrase)
                    startActivity(intent)
                }
            }
        }
    }

    private fun startAddThread() {
        addThread = object : Thread() {
            override fun run() {
                while (!isInterrupted) {
                    try {
                        val computer = computersToAdd.take()
                        doAddPc(computer)
                    } catch (e: InterruptedException) {
                        return
                    }
                }
            }
        }.apply {
            name = "UI - AddComputerManually"
            start()
        }
    }

    private fun joinAddThread() {
        val thread = addThread ?: return
        thread.interrupt()
        try {
            thread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
            Thread.currentThread().interrupt()
        }
        addThread = null
    }

    override fun onStop() {
        super.onStop()
        Dialog.closeDialogs()
        SpinnerDialog.closeDialogs(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (managerBinder != null) {
            joinAddThread()
            unbindService(serviceConnection)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        val action = intent.action
        val data = intent.data
        val server: String?
        val query: String?
        if (Intent.ACTION_VIEW == action && data != null) {
            val port = data.port
            if (port == -1) {
                val urlAction = data.host
                if (urlAction == "launch") {
                    val intent = Intent(this, ShortcutTrampoline::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                    intent.putExtra(AppView.UUID_EXTRA, data.getQueryParameter("host_uuid"))
                    intent.putExtra(AppView.NAME_EXTRA, data.getQueryParameter("host_name"))
                    intent.putExtra(Game.EXTRA_APP_UUID, data.getQueryParameter("app_uuid"))
                    intent.putExtra(Game.EXTRA_APP_NAME, data.getQueryParameter("app_name"))
                    intent.putExtra(Game.EXTRA_APP_ID, data.getQueryParameter("app_id"))
                    finish()
                    startActivity(intent)
                    return
                }
            }

            server = data.authority
            query = data.query
        } else {
            server = null
            query = null
        }

        UiHelper.setLocale(this)
        setContentView(R.layout.activity_add_computer_manually)
        UiHelper.notifyNewRootView(this)

        hostText = findViewById(R.id.hostTextView)
        hostText.imeOptions = EditorInfo.IME_ACTION_DONE
        hostText.setOnEditorActionListener { _, actionId, keyEvent ->
            if (
                actionId == EditorInfo.IME_ACTION_DONE ||
                keyEvent != null &&
                keyEvent.action == KeyEvent.ACTION_DOWN &&
                keyEvent.keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                handleDoneEvent()
            } else if (actionId == EditorInfo.IME_ACTION_PREVIOUS) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(hostText.windowToken, 0)
                false
            } else {
                false
            }
        }

        findViewById<View>(R.id.addPcButton).setOnClickListener {
            handleDoneEvent()
        }

        bindService(
            Intent(this, ComputerManagerService::class.java),
            serviceConnection,
            Service.BIND_AUTO_CREATE
        )

        if (data == null || server == null || query == null) {
            return
        }

        hostText.text = server
        if (query.isNotEmpty()) {
            val hostName = data.getQueryParameter("name")?.takeIf { it.isNotEmpty() }
                ?.let { "$it ($server)" }
                ?: server

            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.pair_pc_confirm_title)
                .setMessage(getString(R.string.pair_pc_confirm_message, hostName))
                .setPositiveButton(getString(R.string.proceed)) { dialog, _ ->
                    dialog.dismiss()
                    finish()
                    computersToAdd.add("$server?$query")
                }
                .setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
                .create()
            dialog.show()
        }
    }

    private fun handleDoneEvent(): Boolean {
        val hostAddress = hostText.text.toString().trim()
        if (hostAddress.isEmpty()) {
            Toast.makeText(
                this,
                resources.getString(R.string.addpc_enter_ip),
                Toast.LENGTH_LONG
            ).show()
            return true
        }

        computersToAdd.add(hostAddress)
        return false
    }
}
