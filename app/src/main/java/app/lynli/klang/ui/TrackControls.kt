package app.lynli.klang.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.data.Track

/** Check the middle half of a row, where the controls sit, rather than one clipped pixel. */
internal fun controlsAreVisible(offset: Int, size: Int, viewportStart: Int, viewportEnd: Int): Boolean {
    if (size <= 0 || viewportEnd <= viewportStart) return false
    return offset + size / 4 >= viewportStart && offset + size * 3 / 4 <= viewportEnd
}

@Composable
internal fun FavoriteButton(track: Track, state: MusicState, model: MusicViewModel, ink: Color) {
    val favorite = track.id in state.favorites
    IconToggleButton(favorite, { model.favorite(track) }) {
        Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (favorite) "Remove ${track.title} from favorites" else "Favorite ${track.title}", tint = ink)
    }
}

@Composable
internal fun ActiveTrackControls(track: Track, state: MusicState, model: MusicViewModel, ink: Color) {
    Row {
        IconButton(model::togglePlay) {
            Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (state.playing) "Pause ${track.title}" else "Play ${track.title}", tint = ink)
        }
        FavoriteButton(track, state, model, ink)
    }
}
