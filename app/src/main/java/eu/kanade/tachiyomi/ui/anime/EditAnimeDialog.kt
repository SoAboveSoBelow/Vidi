// AM (CUSTOM_INFORMATION) -->
package eu.kanade.tachiyomi.ui.anime

import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import coil3.load
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import eu.kanade.presentation.components.AlertDialog
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.databinding.EditAnimeDialogBinding
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.coroutines.CoroutineScope
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.anime.model.Anime
import tachiyomi.i18n.MR
import tachiyomi.i18n.animiru.AMMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.isLocal

@Composable
fun EditAnimeDialog(
    anime: Anime,
    onDismissRequest: () -> Unit,
    onPositiveClick: (
        title: String?,
        author: String?,
        artist: String?,
        description: String?,
        status: Long?,
    ) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var binding by remember {
        mutableStateOf<EditAnimeDialogBinding?>(null)
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    val binding = binding ?: return@TextButton
                    onPositiveClick(
                        binding.title.text.toString(),
                        binding.animeAuthor.text.toString(),
                        binding.animeArtist.text.toString(),
                        binding.animeDescription.text.toString(),
                        binding.status.selectedItemPosition.let {
                            when (it) {
                                1 -> SAnime.ONGOING
                                2 -> SAnime.COMPLETED
                                3 -> SAnime.LICENSED
                                4 -> SAnime.PUBLISHING_FINISHED
                                5 -> SAnime.CANCELLED
                                6 -> SAnime.ON_HIATUS
                                else -> null
                            }
                        }?.toLong(),
                    )
                    onDismissRequest()
                },
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                AndroidView(
                    factory = { factoryContext ->
                        EditAnimeDialogBinding.inflate(LayoutInflater.from(factoryContext))
                            .also { binding = it }
                            .apply {
                                onViewCreated(anime, factoryContext, this, scope)
                            }
                            .root
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

private fun loadCover(anime: Anime, context: Context, binding: EditAnimeDialogBinding) {
    binding.animeCover.load(anime) {
        transformations(RoundedCornersTransformation(4.dpToPx.toFloat()))
    }
}

private fun onViewCreated(anime: Anime, context: Context, binding: EditAnimeDialogBinding, scope: CoroutineScope) {
    loadCover(anime, context, binding)

    val statusAdapter: ArrayAdapter<String> = ArrayAdapter(
        context,
        android.R.layout.simple_spinner_dropdown_item,
        listOf(
            MR.strings.label_default,
            MR.strings.ongoing,
            MR.strings.completed,
            MR.strings.licensed,
            MR.strings.publishing_finished,
            MR.strings.cancelled,
            MR.strings.on_hiatus,
        ).map { context.stringResource(it) },
    )

    binding.status.adapter = statusAdapter
    if (anime.status != anime.ogStatus) {
        binding.status.setSelection(
            when (anime.status.toInt()) {
                SAnime.UNKNOWN -> 0
                SAnime.ONGOING -> 1
                SAnime.COMPLETED -> 2
                SAnime.LICENSED -> 3
                SAnime.PUBLISHING_FINISHED, 61 -> 4
                SAnime.CANCELLED, 62 -> 5
                SAnime.ON_HIATUS, 63 -> 6
                else -> 0
            },
        )
    }

    if (anime.isLocal()) {
        if (anime.title != anime.url) {
            binding.title.setText(anime.title)
        }

        binding.title.hint = context.stringResource(AMMR.strings.title_hint, anime.url)
        binding.animeAuthor.setText(anime.author.orEmpty())
        binding.animeArtist.setText(anime.artist.orEmpty())
        binding.animeDescription.setText(anime.description.orEmpty())
    } else {
        if (anime.title != anime.ogTitle) {
            binding.title.append(anime.title)
        }
        if (anime.author != anime.ogAuthor) {
            binding.animeAuthor.append(anime.author.orEmpty())
        }
        if (anime.artist != anime.ogArtist) {
            binding.animeArtist.append(anime.artist.orEmpty())
        }
        if (anime.description != anime.ogDescription) {
            binding.animeDescription.append(anime.description.orEmpty())
        }

        binding.title.hint = context.stringResource(AMMR.strings.title_hint, anime.ogTitle)
        if (anime.ogAuthor != null) {
            binding.animeAuthor.hint = context.stringResource(AMMR.strings.author_hint, anime.ogAuthor!!)
        }
        if (anime.ogArtist != null) {
            binding.animeArtist.hint = context.stringResource(AMMR.strings.artist_hint, anime.ogArtist!!)
        }
        if (!anime.ogDescription.isNullOrBlank()) {
            binding.animeDescription.hint =
                context.stringResource(
                    AMMR.strings.description_hint,
                    anime.ogDescription!!.replace("\n", " ").chop(20),
                )
        }
    }

}

