package com.pradomo.data

import android.content.Context
import com.pradomo.map.MapSample

/** Persists/loads driven-path samples per mower (see [MapSampleEntity]). */
class MapRepository(context: Context) {
    private val dao = MapDatabase.get(context).mapDao()

    suspend fun track(mowerId: String): List<MapSample> =
        dao.track(mowerId).map {
            MapSample(it.tMillis, it.x, it.y, it.heading, it.bladeOn, it.cmdLinear, it.cmdAngular)
        }

    suspend fun append(mowerId: String, s: MapSample) =
        dao.insert(
            MapSampleEntity(
                mowerId = mowerId, tMillis = s.tMillis, x = s.x, y = s.y, heading = s.heading,
                bladeOn = s.bladeOn, cmdLinear = s.cmdLinear, cmdAngular = s.cmdAngular,
            ),
        )

    suspend fun clear(mowerId: String) = dao.clear(mowerId)
}
