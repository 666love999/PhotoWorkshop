package com.albumworkshop

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class AlbumWorkshopApp : Application(), ImageLoaderFactory {

    /** 让 Coil 能直接出视频首帧，视频列表才有缩略图 */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
}
