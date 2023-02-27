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
