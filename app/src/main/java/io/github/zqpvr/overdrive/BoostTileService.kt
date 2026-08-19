package io.github.zqpvr.overdrive

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings toggle.
 *
 * The tile is only bound while the shade is open, so it can never be the host that keeps the
 * effect alive; it just flips the state and lets [BoostController] bring a real host up.
 */
class BoostTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        BoostState.ensureInit(this)
        render()
    }

    override fun onClick() {
        super.onClick()
        BoostState.ensureInit(this)
        BoostController.setEnabled(this, !BoostState.enabled.value)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val on = BoostState.enabled.value

        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_overdrive)
        tile.label = getString(R.string.app_name)
        tile.subtitle = if (on) "+%.0f dB".format(BoostState.gainDb.value) else "Off"
        tile.updateTile()
    }
}
