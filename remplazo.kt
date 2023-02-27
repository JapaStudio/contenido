class MainActivity : AppCompatActivity(), AnalyticsListener {
    private val andServer by lazy {
        AndServer.webServer(this)
            .port(SERVER_PORT)
            .timeout(10, TimeUnit.SECONDS)
            .build()
    }
    private val player by lazy {
        SimpleExoPlayer.Builder(this).setLoadControl(asapLoadControl).build()
    }
    private val asapLoadControl by lazy {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1000,
                60000,
                1000,
                1000,
            ).build()
    }
    private val dataSourceFactory by lazy {
        DefaultDataSourceFactory(
            this,
            Util.getUserAgent(this, "Mozilla/4.0 (compatible; MSIE 6.0; ztebw V1.0)"),
        )
    }
    private var currentStation: TVStation? = null
        set(value) {
            if (field?.id == value?.id) {
                return
            }
            field = value
            notifyTvStationChange()
        }
    private var configPageUrl = ""
        set(value) {
            if (field == value) {
                return
            }
            field = value
            tvInfo.text = getString(R.string.fmt_cfg_page_, field)
            imgQr.imageBitmap = QRCode.from(field).bitmap()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView.player = player
        player.addAnalyticsListener(this)

        andServer.startup()
        //start server
        lifecycleScope.launchWhenCreated {
            while (!isDestroyed) {
                if (!andServer.isRunning) {
                    tvInfo.textResource = R.string.starting_config_server
                    delay(1000)
                }
                val ip = wifiManager?.connectionInfo?.ipAddress
                if (ip == null) {
                    delay(1000)
                    continue
                }
                val ip4String = intToIP(ip)
                configPageUrl = "http://$ip4String:$SERVER_PORT/index.html"
                //QR code
                delay(5000)
            }
        }
        //inject initial data
        lifecycleScope.launchWhenCreated {
            if (BuildConfig.DEBUG) db.tvStationDao().clear()
            val savedStations = db.tvStationDao().getAll()
            if (savedStations.isEmpty()) {
                val defaultStations = withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val request = Request.Builder()
                        .url("https://raw.githubusercontent.com/JapaStudio/contenido/main/default_tv_stations.json")
                        .build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val responseJson = response.body?.string()
                        gson.fromJson(responseJson, Array<TVStation>::class.java)
                    } else {
                        throw IOException("Error al obtener los canales de TV")
                    }
                }
                db.tvStationDao().insert(*defaultStations)
            }
            changeStation(true)
        }



    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> changeStation(false)
            KeyEvent.KEYCODE_DPAD_DOWN -> changeStation(true)
            KeyEvent.KEYCODE_DPAD_CENTER -> viewInfo.visibility =
                if (viewInfo.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            changeStation(true)
        }
        return super.onTouchEvent(event)
    }

    override fun onPause() {
        super.onPause()
        player.stop()
    }

    override fun onResume() {
        super.onResume()
        if (this.currentStation != null) {
            notifyTvStationChange()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        andServer.shutdown()
        player.release()
    }

    private fun intToIP(ip: Int): String {
        return (ip and 0xFF).toString() + "." +
                (ip shr 8 and 0xFF) + "." +
                (ip shr 16 and 0xFF) + "." +
                (ip shr 24 and 0xFF)
    }

    private var changingJob: Job? = null
    private fun changeStation(nextOrPrevious: Boolean) {
        changingJob?.cancel()
        changingJob = lifecycleScope.launchWhenCreated {
            val savedStations = db.tvStationDao().getAll()
            val index = savedStations.indexOfFirst {
                it.id == currentStation?.id
            } + if (nextOrPrevious) 1 else -1
            val targetIndex = when {
                index > savedStations.lastIndex -> 0
                index < 0 -> savedStations.lastIndex
                else -> index
            }
            currentStation = savedStations.getOrNull(targetIndex)

            channelIndex.text = getString(
                R.string.fmt_channel_index,
                if (targetIndex < 0) "--" else (targetIndex + 1).toString(),
                savedStations.size.toString()
            )
            channelTitle.text = currentStation?.title ?: "--"
            channelInfo.visibility = View.VISIBLE
            delay(2000)
            channelInfo.visibility = View.INVISIBLE
        }
    }

    private fun notifyTvStationChange() {
        val currentStation = this.currentStation
        if (currentStation == null) {
            longToast(R.string.no_saved_stations)
            return
        }
        val uri = Uri.parse(currentStation.m3u8)
        val type = Util.inferContentType(uri)
        val ms = when (type) {
            C.TYPE_DASH -> DashMediaSource.Factory(dataSourceFactory)
            C.TYPE_HLS -> HlsMediaSource.Factory(dataSourceFactory)
            C.TYPE_SS -> SsMediaSource.Factory(dataSourceFactory)
            C.TYPE_OTHER -> ProgressiveMediaSource.Factory(dataSourceFactory)
            else -> {
                toast(str(R.string.fmt_unsupported_content_type, type.toString()))
                return
            }
        }.createMediaSource(MediaItem.fromUri(uri))
        player.setMediaSource(ms)
        player.prepare()
        player.playWhenReady = true
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        super.onPlaybackStateChanged(eventTime, state)
        viewLoading.visibility = when (state) {
            Player.STATE_BUFFERING -> View.VISIBLE
            else -> View.INVISIBLE
        }
    }
}

