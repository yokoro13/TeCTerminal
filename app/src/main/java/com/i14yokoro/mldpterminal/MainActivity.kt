package com.i14yokoro.mldpterminal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.StrictMode
import android.support.v7.app.AppCompatActivity
import android.text.Editable
import android.text.Html
import android.text.SpannableString
import android.text.TextWatcher
import android.util.Log
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

import com.i14yokoro.mldpterminal.bluetooth.MldpBluetoothScanActivity
import com.i14yokoro.mldpterminal.bluetooth.MldpBluetoothService

import java.nio.charset.StandardCharsets
import kotlin.experimental.and
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var inputEditText: EditText//ディスプレイのEditText

    // 設定保存用
    private lateinit var prefs: SharedPreferences
    private var state = State.STARTING

    private var bleDeviceName: String = "\u0000"
    private var bleDeviceAddress: String = "\u0000" // 接続先の情報
    private lateinit var connectTimeoutHandler: Handler
    private var bleService: MldpBluetoothService? = null

    private var bleAutoConnect: Boolean = false //自動接続するか

    private lateinit var escapeSequence: EscapeSequence
    private lateinit var termBuffer: TerminalBuffer

    private var eStart: Int = 0
    private var eCount: Int = 0

    private lateinit var escapeString: StringBuilder // 受信したエスケープシーケンスを格納
    private var result = ""
    private lateinit var spannable: SpannableString

    private var screenRowSize: Int = 0
    private var screenColumnSize: Int = 0

    private var isMovingCursor = false // カーソル移動中ならtrue
    private var btnCtl = false      // CTLボタンを押したらtrue
    private var isNotSending = false   // RN側に送りたくないものがあるときはfalseにする
    private var isDisplaying = false   // 画面更新中はtrue
    private var isSending = false      // RNにデータを送信しているときtrue
    private var isOverWriting = false  // 文字を上書きするときtrue
    private var sendCtl = false        // コントロールキーを使った制御信号を送るとtrue
    private var isEscapeSequence = false   // エスケープシーケンスを受信するとtrue
    //private boolean isOutOfScreen = false;    //カーソルが画面外か

    private var stack = 0  // 処理待ちの文字数

    private val handler = Handler()
    private val time = 3 //

    private val mActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.removeItem(android.R.id.paste)
            menu.removeItem(android.R.id.cut)
            menu.removeItem(android.R.id.copy)
            menu.removeItem(android.R.id.selectAll)
            menu.removeItem(android.R.id.addToDictionary)
            menu.removeItem(android.R.id.startSelectingText)
            menu.removeItem(android.R.id.selectTextMode)
            return false
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.removeItem(android.R.id.paste)
            menu.removeItem(android.R.id.cut)
            menu.removeItem(android.R.id.copy)
            menu.removeItem(android.R.id.selectAll)
            menu.removeItem(android.R.id.addToDictionary)
            menu.removeItem(android.R.id.startSelectingText)
            menu.removeItem(android.R.id.selectTextMode)
            menu.close()
            return false
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {

        }
    }

    private val mInputTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            eStart = start//文字列のスタート位置
            eCount = count//追加される文字

            if (state == State.CONNECTED && count > before) {
                if (!isNotSending) {
                    val send = s.subSequence(start + before, start + count).toString()
                    Log.d("RNsend", send)
                    isSending = true
                    if (btnCtl) {
                        if (send.matches("[\\x5f-\\x7e]".toRegex())) {
                            val sendB = byteArrayOf((send.toByteArray()[0] and 0x1f))
                            bleService!!.writeMLDP(sendB)
                            btnCtl = false
                            sendCtl = true
                        }
                        btnCtl = false
                    }
                    if (!sendCtl) {
                        bleService!!.writeMLDP(send)
                    } else {
                        sendCtl = false
                    }
                }
            }
        }

        override fun afterTextChanged(s: Editable) {
            if (s.isEmpty()){
                return
            }
            val str = s.subSequence(eStart, eStart + eCount).toString()//入力文字

            handler.removeCallbacks(updateDisplay)
            if (str.matches("[\\x20-\\x7f\\x0a\\x0d]".toRegex()) && !isSending) {
                if (!isDisplaying) {
                    addList(str)
                    handler.postDelayed(updateDisplay, time.toLong())
                }
            } else { //ASCIIじゃなければ入力前の状態にもどす
                isSending = false
            }
        }
    }

    private val bleServiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MldpBluetoothService.ACTION_BLE_CONNECTED -> {
                    connectTimeoutHandler.removeCallbacks(abortConnection)
                    Log.i(TAG, "Received intent  ACTION_BLE_CONNECTED")
                    state = State.CONNECTED
                    updateConnectionState()

                }
                MldpBluetoothService.ACTION_BLE_DISCONNECTED -> {
                    Log.i(TAG, "Received intent ACTION_BLE_DISCONNECTED")
                    state = State.DISCONNECTED
                    updateConnectionState()
                }
                MldpBluetoothService.ACTION_BLE_DATA_RECEIVED -> {
                    val receivedData = intent.getStringExtra(MldpBluetoothService.INTENT_EXTRA_SERVICE_DATA)
                            ?: return
                    var cnt = 1
                    Log.d("debug****", "receivedData$receivedData")

                    val splitData = receivedData.split("".toRegex()).toTypedArray()

                    handler.removeCallbacks(updateDisplay)
                    stack += splitData.size - 2
                    Log.d("stack***", "stackLength$stack")
                    val utf = receivedData.toByteArray(StandardCharsets.UTF_8)

                    for (charCode in utf) {
                        when (charCode) {
                            0x08.toByte()   // KEY_BS
                            -> moveCursorX(-1)
                            0x09.toByte()    // KEY_HT
                            -> if (termBuffer.cursorX + (8 - termBuffer.cursorX % 8) < screenRowSize) {
                                escapeSequence.moveRight(8 - termBuffer.cursorX % 8)
                            } else {
                                moveCursorX(screenRowSize - 1)
                            }
                            0x7f.toByte()    // KEY_DEL
                            -> {
                            }
                            0x0a.toByte()    // KEY_LF
                            -> {
                                Log.d("debug****", "KEY_LF")
                                isNotSending = true
                                addList("\n")
                                isNotSending = false
                            }
                            0x0d.toByte()    // KEY_CR
                            -> {
                                Log.d("debug****", "KEY_CR")
                                termBuffer.cursorX = 0
                                moveToSavedCursor()
                            }
                            0x1b.toByte()   // KEY_ESC
                            -> {
                                Log.d(TAG, "receive esc")
                                isEscapeSequence = true
                                escapeString.setLength(0)
                            }
                            else -> if (isEscapeSequence) {
                                escapeString.append(splitData[cnt])
                                if (splitData[cnt].matches("[A-HJKSTZfm]".toRegex())) {
                                    checkEscapeSequence()
                                    isEscapeSequence = false
                                }
                            } else {
                                if (cnt <= receivedData.length) {
                                    if (splitData[cnt] == "\u0020") {
                                        splitData[cnt] = " "
                                    }
                                    isNotSending = true
                                    addList(splitData[cnt])
                                    isNotSending = false
                                }
                            }
                        }
                        stack--
                        Log.d("stack***", "stackLength$stack")
                        cnt++
                        if (stack == 0) {
                            Log.d("TermDisplay****", "stack is 0")
                            handler.postDelayed(updateDisplay, time.toLong())
                        }
                    }
                }
            }
        }
    }

    // 切断
    private val abortConnection = Runnable {
        if (state == State.CONNECTING) {
            bleService!!.disconnect()
        }
    }

    // bluetoothのserviceを使う
    private val bleServiceConnection = object : ServiceConnection {

        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
            val binder = service as MldpBluetoothService.LocalBinder
            bleService = binder.service
            if (!bleService!!.isBluetoothRadioEnabled) {
                state = State.ENABLING
                updateConnectionState()
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQ_CODE_ENABLE_BT)
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            bleService = null
        }
    }

    // １行に収まる文字数を返す
    private val maxRowLength: Int
        get() {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val p = Point()
            wm.defaultDisplay.getSize(p)

            return p.x / textWidth
        }

    // １列に収まる文字数を返す
    private val maxColumnLength: Int
        get() {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val p = Point()
            wm.defaultDisplay.getSize(p)

            val height = p.y - 100
            val text = textHeight.toInt()

            return height / text - 1
        }

    // テキストの文字の横幅を返す
    private// TypefaceがMonospace 「" "」の幅を取得
    val textWidth: Int
        get() {
            val paint = Paint()
            paint.textSize = inputEditText.textSize
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            return paint.measureText(" ").toInt()
        }

    // テキストの文字の高さを返す
    private val textHeight: Float
        get() {
            val paint = Paint()
            paint.textSize = inputEditText.textSize
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            val fontMetrics = paint.fontMetrics
            return abs(fontMetrics.top) + abs(fontMetrics.bottom)
        }

    // 選択中の行番号を返す
    private val selectRowIndex: Int
        get() = if (termBuffer.cursorY + termBuffer.topRow <= 0) {
            0
        } else termBuffer.cursorY + termBuffer.topRow

    // 現在の行のテキストを返す
    private val selectRowText: String
        get() = termBuffer.getRowText(selectRowIndex)

    // 画面更新を非同期で行う
    private val updateDisplay = {
        changeDisplay()
        moveToSavedCursor()
    }

    // 接続状況
    private enum class State {
        STARTING, ENABLING, SCANNING, CONNECTING, CONNECTED, DISCONNECTED, DISCONNECTING
    } //state

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {

        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputEditText = findViewById(R.id.main_display)
        inputEditText.customSelectionActionModeCallback = mActionModeCallback
        inputEditText.addTextChangedListener(mInputTextWatcher)
        inputEditText.setTextIsSelectable(false)

        screenRowSize = maxRowLength
        screenColumnSize = maxColumnLength
        termBuffer = TerminalBuffer(screenRowSize, screenColumnSize)
        escapeSequence = EscapeSequence(termBuffer) //今のContentを渡す
        Log.d(TAG, "maxRow " + maxRowLength + "maxColumn" + maxColumnLength)

        escapeString = StringBuilder()
        state = State.STARTING
        connectTimeoutHandler = Handler()


        findViewById<View>(R.id.btn_up).setOnClickListener {
            if (state == State.CONNECTED) {
                bleService!!.writeMLDP("\u001b" + "[A")
                if (termBuffer.cursorY > 0) {
                    moveToSavedCursor()
                }
            }
            escapeSequence.moveUp(1)
            moveToSavedCursor()
        }

        findViewById<View>(R.id.btn_down).setOnClickListener {
            if (state == State.CONNECTED) {
                bleService!!.writeMLDP("\u001b" + "[B")
                if (termBuffer.cursorY < inputEditText.lineCount - 1) {
                    moveToSavedCursor()
                }
            }
            escapeSequence.moveDown(1)
            moveToSavedCursor()
        }

        findViewById<View>(R.id.btn_right).setOnClickListener {
            if (state == State.CONNECTED) {
                bleService!!.writeMLDP("\u001b" + "[C")
            }
            if (selectRowIndex == termBuffer.totalColumns - 1) {
                if (termBuffer.cursorX < termBuffer.getRowLength(selectRowIndex)) {
                    moveToSavedCursor()
                }
            }
        }
        findViewById<View>(R.id.btn_left).setOnClickListener {
            if (state == State.CONNECTED) {
                bleService!!.writeMLDP("\u001b" + "[D")
            }
            if (selectRowIndex == termBuffer.totalColumns - 1) {
                if (termBuffer.cursorX > 0) {
                    moveToSavedCursor()
                }
            }
        }

        findViewById<View>(R.id.btn_esc).setOnClickListener { if (state == State.CONNECTED) bleService!!.writeMLDP("\u001b") }

        findViewById<View>(R.id.btn_tab).setOnClickListener { if (state == State.CONNECTED) bleService!!.writeMLDP("\u0009") }

        findViewById<View>(R.id.btn_ctl).setOnClickListener { btnCtl = true }

        //SDK23以降はBLEをスキャンするのに位置情報が必要
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 0)
        }

        //自動接続
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        bleAutoConnect = prefs.getBoolean(PREFS_AUTO_CONNECT, false)
        if (bleAutoConnect) {
            bleDeviceName = prefs.getString(PREFS_NAME, "\u0000")
            bleDeviceAddress = prefs.getString(PREFS_ADDRESS, "\u0000")
        }

        //画面タッチされた時のイベント
        inputEditText.setOnTouchListener(object : View.OnTouchListener {
            var oldY: Int = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // タップした時に ScrollViewのScrollY座標を保持
                        oldY = event.rawY.toInt()
                        Log.d(TAG, "action down")
                        showKeyboard()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // 指を動かした時に、現在のscrollY座標とoldYを比較して、違いがあるならスクロール状態とみなす
                        Log.d(TAG, "action move")
                        hideKeyboard()
                        if (oldY > event.rawY) {
                            scrollDown()
                        }
                        if (oldY < event.rawY) {
                            scrollUp()
                        }
                    }
                    else -> {
                    }
                }
                return false
            }
        })

        inputEditText.setOnKeyListener { _, i, keyEvent ->
            if (keyEvent.action == KeyEvent.ACTION_DOWN && i == KeyEvent.KEYCODE_DEL) {
                if (state == State.CONNECTED) {
                    bleService!!.writeMLDP("\u0008")
                } else {
                    moveCursorX(-1)
                    moveToSavedCursor()
                }
                return@setOnKeyListener true
            }
            false
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(bleServiceReceiver, bleServiceIntentFilter())
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(bleServiceReceiver)
    }

    // デバイスデータを保存する
    public override fun onStop() {
        super.onStop()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.clear()
        if (bleAutoConnect) {
            editor.putString(PREFS_NAME, bleDeviceName)
            editor.putString(PREFS_ADDRESS, bleDeviceAddress)
        }
        editor.apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        addNewLine("onDestroy")
        unbindService(bleServiceConnection)
        bleService = null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_terminal_menu, menu)
        if (state == State.CONNECTED) {
            menu.findItem(R.id.menu_disconnect).isVisible = true
            menu.findItem(R.id.menu_connect).isVisible = false
        } else {
            menu.findItem(R.id.menu_disconnect).isVisible = false
            if (bleDeviceAddress != "\u0000") {
                menu.findItem(R.id.menu_connect).isVisible = true
            } else {
                menu.findItem(R.id.menu_connect).isVisible = true
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_scan -> {
                startScan()
                return true
            }

            R.id.menu_connect -> {
                connectWithAddress(bleDeviceAddress)
                return true
            }

            R.id.menu_disconnect -> {
                state = State.DISCONNECTING
                updateConnectionState()
                bleService!!.disconnect()
                //unbindService(bleServiceConnection);
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // エスケープシーケンスの処理
    private fun checkEscapeSequence() {
        val length = escapeString.length
        val mode = escapeString[length - 1]
        var move = 1
        var hMove = 1
        val semicolonPos: Int

        if (length != 2) {
            if (!(mode == 'H' || mode == 'f')) {
                move = Integer.parseInt(escapeString.substring(1, length - 2))
            } else {
                semicolonPos = escapeString.indexOf(";")
                if (semicolonPos != 2) {
                    move = Integer.parseInt(escapeString.substring(2, semicolonPos - 1))
                }
                if (escapeString[semicolonPos + 1] != 'H' || escapeString[semicolonPos + 1] != 'f') {
                    hMove = Integer.parseInt(escapeString.substring(semicolonPos + 1, length - 2))
                }
            }
        }

        when (mode) {
            'A' -> escapeSequence.moveUp(move)
            'B' -> escapeSequence.moveDown(move)
            'C' -> escapeSequence.moveRight(move)
            'D' -> escapeSequence.moveLeft(move)
            'E' -> escapeSequence.moveDownToRowLead(move)
            'F' -> escapeSequence.moveUpToRowLead(move)
            'G' -> escapeSequence.moveCursor(move)
            'H', 'f' -> escapeSequence.moveCursor(hMove, move)
            'J' -> escapeSequence.clearDisplay(move)
            'K' -> escapeSequence.clearRow(move)
            'S' -> escapeSequence.scrollNext(move)
            'T' -> escapeSequence.scrollBack(move)
            'Z' -> {
                //TODO 画面のサイズを送信するエスケープシーケンスの実装
                isSending = true
                bleService!!.writeMLDP(maxRowLength.toString())
                bleService!!.writeMLDP(maxColumnLength.toString())
            }
            'm' -> {
                escapeSequence.selectGraphicRendition(move)
                inputEditText.setTextColor(termBuffer.charColor)
            }
            else -> {
            }
        }
    }

    // 接続
    private fun connectWithAddress(address: String): Boolean {
        state = State.CONNECTING
        updateConnectionState()
        connectTimeoutHandler.postDelayed(abortConnection, CONNECT_TIME)
        return bleService!!.connect(address)
    }

    // 周りにあるBLEをスキャン
    private fun startScan() {
        if (bleService != null) {
            bleService!!.disconnect()
            state = State.DISCONNECTING
            updateConnectionState()
        }

        //else {
        val bleServiceIntent = Intent(this@MainActivity, MldpBluetoothService::class.java)
        this.bindService(bleServiceIntent, bleServiceConnection, Context.BIND_AUTO_CREATE)
        //}

        val bleScanActivityIntent = Intent(this@MainActivity, MldpBluetoothScanActivity::class.java)
        startActivityForResult(bleScanActivityIntent, REQ_CODE_SCAN_ACTIVITY)
    }

    // bluetoothの接続状況を更新
    private fun updateConnectionState() {
        runOnUiThread {
            when (state) {
                State.STARTING, State.ENABLING, State.SCANNING, State.DISCONNECTED -> {
                    stack = 0
                }
                State.CONNECTED -> {
                    isNotSending = true
                    addNewLine(LF + "connect to " + bleDeviceName)
                }
                State.DISCONNECTING -> {
                    isNotSending = true
                    addNewLine(LF + "disconnected from " + bleDeviceName)
                }
                State.CONNECTING -> TODO()
            }//bleService.writeMLDP("MLDP\r\nApp:on\r\n");

            invalidateOptionsMenu()
        }
    }

    // 別Activityからの処理結果をうけとる
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode == REQ_CODE_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                if (!bleAutoConnect || bleDeviceAddress == "\u0000") {
                    startScan()
                }
            }
            return
        } else if (requestCode == REQ_CODE_SCAN_ACTIVITY) {
            if (resultCode == Activity.RESULT_OK) {
                bleDeviceAddress = intent!!.getStringExtra(MldpBluetoothScanActivity.INTENT_EXTRA_SCAN_ADDRESS)
                bleDeviceName = intent.getStringExtra(MldpBluetoothScanActivity.INTENT_EXTRA_SCAN_NAME)
                bleAutoConnect = intent.getBooleanExtra(MldpBluetoothScanActivity.INTENT_EXTRA_SCAN_AUTO_CONNECT, false)
                if (bleDeviceAddress == "\u0000") {
                    state = State.DISCONNECTED
                    updateConnectionState()
                } else {
                    state = State.CONNECTING
                    updateConnectionState()
                    if (!connectWithAddress(bleDeviceAddress)) {
                        Log.d(TAG, "connect is failed")
                    }
                }
            } else {
                state = State.DISCONNECTED
                updateConnectionState()
            }
        }
        super.onActivityResult(requestCode, resultCode, intent)
    }

    // 新しい行を追加
    private fun addNewLine(newText: String) {
        for (element in newText) {
            isNotSending = true
            inputEditText.append(element.toString())
        }
        inputEditText.append("\n")
        termBuffer.cursorX = 0
        isNotSending = false
    }

    // キーボードを表示させる
    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val v = currentFocus
        if (v != null)
            imm.showSoftInput(v, 0)
    }

    //　キーボードを隠す
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val v = currentFocus
        if (v != null)
            imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ディスプレイに文字を表示する
    private fun changeDisplay() {
        isDisplaying = true
        isNotSending = true

        inputEditText.text.clear()
        if (!termBuffer.isColorChange) {
            inputEditText.append(termBuffer.display())
        } else {
            spannable = SpannableString(termBuffer.display())
            result = HtmlParser.toHtml(spannable)
            inputEditText.append(Html.fromHtml(result))
        }
        isDisplaying = false
        isNotSending = false
    }

    // カーソルを横方向にx移動させる
    private fun moveCursorX(x: Int) {
        termBuffer.cursorX = termBuffer.cursorX + x
    }

    // カーソルを縦方向にy移動させる
    private fun moveCursorY(y: Int) {
        termBuffer.cursorY = termBuffer.cursorY + y
    }

    // カーソルを保持している座標に移動させる
    private fun moveToSavedCursor() {
        if (!isMovingCursor) {
            isMovingCursor = true

            val cursor: Int = termBuffer.cursorY * termBuffer.screenRowSize + termBuffer.cursorX

            Log.d("debug***", "cursor$cursor")
            if (cursor >= 0) {
                inputEditText.setSelection(cursor)
            }
            isMovingCursor = false
        }
    }

    // 画面を上にスクロールする
    private fun scrollUp() {
        if (termBuffer.totalColumns > screenColumnSize) {
            if (termBuffer.topRow - 1 >= 0) {
                //表示する一番上の行を１つ上に
                termBuffer.moveTopRow(-1)
                // カーソルが画面内にある
                if (termBuffer.topRow <= termBuffer.currentRow && termBuffer.currentRow < termBuffer.topRow + screenColumnSize) {
                    setEditable(true)
                    moveCursorY(1)
                } else { //画面外
                    // 0のときは表示させる
                    if (termBuffer.topRow == 0) {
                        setEditable(true)
                    } else {
                        setEditable(false)
                    }
                }
                if (stack == 0) {
                    changeDisplay()
                    moveToSavedCursor()
                }
            }
        }
    }

    // 画面を下にスクロールする
    private fun scrollDown() {
        if (termBuffer.totalColumns > screenColumnSize) {
            // 一番下の行までしか表示させない
            if (termBuffer.topRow + screenColumnSize < termBuffer.totalColumns) {
                //表示する一番上の行を１つ下に
                termBuffer.moveTopRow(1)
                if (termBuffer.topRow < termBuffer.currentRow && termBuffer.currentRow <= termBuffer.topRow + screenColumnSize - 1) {
                    setEditable(true)
                    moveCursorY(-1)
                } else {
                    // 一番したのときは表示させる
                    if (termBuffer.currentRow == termBuffer.topRow + screenColumnSize - 1) {
                        setEditable(true)
                    } else {
                        setEditable(false)
                    }
                }
                if (stack == 0) {
                    changeDisplay()
                    moveToSavedCursor()
                }
            }
        }
    }

    // 画面の編集許可
    private fun setEditable(editable: Boolean) {
        if (editable) {
            focusable()
            termBuffer.isOutOfScreen = false
        } else {
            inputEditText.isFocusable = false
            termBuffer.isOutOfScreen = true
        }
    }

    private fun focusable() {
        inputEditText.isFocusable = true
        inputEditText.isFocusableInTouchMode = true
        inputEditText.requestFocus()

    }

    private fun resize(newRowSize: Int, newColumnSize: Int) {
        termBuffer.screenRowSize = newRowSize
        termBuffer.screenColumnSize = newColumnSize
        termBuffer.resize()
    }

    // strをリストに格納
    private fun addList(str: String) {
        if (str.matches("[\\x20-\\x7f\\x0a\\x0d]".toRegex())) {

            // カーソルが画面外で入力があると入力位置に移動
            if (termBuffer.isOutOfScreen) {
                focusable()
                termBuffer.topRow = termBuffer.currentRow - termBuffer.cursorY
                moveToSavedCursor()
            }

            // FIXME わからん 右端で入力があったらカーソル移動させない？
            if (termBuffer.cursorX > selectRowText.length) {
                termBuffer.cursorX = selectRowText.length
                if (selectRowText.contains("\n")) {
                    moveCursorX(-1)
                }
            }

            //
            val inputStr = str[0]

            // カーソルが入力文字列の右端
            if (termBuffer.cursorX == termBuffer.getRowLength(selectRowIndex)) {
                Log.d("termBuffer****", "set")

                // 入力行が一番したの行ならそのまま入力
                if (selectRowIndex == termBuffer.totalColumns - 1) {
                    termBuffer.addText(selectRowIndex, inputStr, termBuffer.charColor)
                } else {
                    // 入力行が一番下じゃないかつ改行コードがないなら文字を追加
                    if (!selectRowText.contains("\n")) {
                        termBuffer.addText(selectRowIndex, inputStr, termBuffer.charColor)
                    }
                }
                // カーソルを移動
                moveCursorX(1)
            } else { //insert
                // カーソルが入力文字列の途中
                Log.d("termBuffer****", "overwrite")
                // 入力が改行じゃなければ文字を上書き
                if (inputStr != LF) {
                    // 上書き
                    termBuffer.setText(termBuffer.cursorX, selectRowIndex, inputStr)
                    // FIXME どういうこと？
                    if (termBuffer.cursorX + 1 < screenRowSize) {
                        isOverWriting = true
                        moveCursorX(1)
                    } else {
                        isOverWriting = false
                    }

                } else { //LF
                    // 途中で行を変える場合
                    // 入力位置が一番下で改行がなければ
                    if (selectRowIndex == termBuffer.totalColumns - 1 && !selectRowText.contains("\n")) {
                        // 入力後の文字数が画面サイズより小さい
                        if (termBuffer.getRowLength(selectRowIndex) + 1 < screenRowSize) {
                            // FIXME ???? 一番最後に改行を追加(ターミナルの場合途中で改行の場合次の行にいくぽい)
                            termBuffer.addText(selectRowIndex, inputStr, termBuffer.charColor)
                        }
                    }
                }
            }

            Log.d(TAG, "ASCII code/ $str")

            // スクロールの処理
            if (inputStr == LF) {
                termBuffer.cursorX = 0
                if (termBuffer.cursorY + 1 >= screenColumnSize) {
                    scrollDown()
                }
                if (termBuffer.cursorY < screenColumnSize) {
                    moveCursorY(1)
                }
            }

            // 右端での入力があったときの時のスクロール
            if (selectRowText.length >= screenRowSize && !selectRowText.contains("\n") && !isOverWriting) {
                termBuffer.cursorX = 0
                if (inputEditText.lineCount >= screenColumnSize) {
                    scrollDown()
                }

                if (termBuffer.cursorY + 1 < screenColumnSize) {
                    moveCursorY(1)
                }
            }

            isOverWriting = false
        }
    }

    companion object {

        private const val LF = '\n'
        private const val TAG = "debug***"
        private const val PREFS = "PREFS"
        private const val PREFS_NAME = "NAME"
        private const val PREFS_ADDRESS = "ADDR"
        private const val PREFS_AUTO_CONNECT = "AUTO"

        // BLE
        private const val REQ_CODE_SCAN_ACTIVITY = 1
        private const val REQ_CODE_ENABLE_BT = 2

        private const val CONNECT_TIME: Long = 5000 //タイムアウトする時間

        //通すActionを記述
        private fun bleServiceIntentFilter(): IntentFilter {
            val intentFilter = IntentFilter()
            intentFilter.addAction(MldpBluetoothService.ACTION_BLE_REQ_ENABLE_BT)
            intentFilter.addAction(MldpBluetoothService.ACTION_BLE_CONNECTED)
            intentFilter.addAction(MldpBluetoothService.ACTION_BLE_DISCONNECTED)
            intentFilter.addAction(MldpBluetoothService.ACTION_BLE_DATA_RECEIVED)
            return intentFilter
        }
    }
}
