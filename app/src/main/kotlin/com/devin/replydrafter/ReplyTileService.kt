package com.devin.replydrafter

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: pull down the shade, tap "Reply with Claude", get the panel over the chat. */
class ReplyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            val ready = ReplyDrafterService.instance != null
            state = if (ready) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            subtitle = if (ready) "Draft a reply" else "Turn on in Accessibility"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        ReplyDrafterService.instance?.openFromTile()
    }
}
