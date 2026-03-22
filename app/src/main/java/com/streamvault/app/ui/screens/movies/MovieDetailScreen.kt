package com.streamvault.app.ui.screens.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamvault.app.R
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.components.rememberCrossfadeImageModel
import com.streamvault.app.ui.components.shell.ContentMetadataStrip
import com.streamvault.app.ui.components.shell.StatusPill
import com.streamvault.app.ui.design.AppColors
import com.streamvault.domain.model.Movie

@Composable
fun MovieDetailScreen(
    onPlay: (Movie) -> Unit,
    onBack: () -> Unit,
    viewModel: MovieDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val movie = uiState.movie

    when {
        uiState.isLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.Canvas),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.movie_loading_details), color = AppColors.TextSecondary)
            }
        }

        movie == null -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.Canvas),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = uiState.error ?: stringResource(R.string.movie_not_found),
                    color = AppColors.Live
                )
            }
        }

        else -> {
            MovieDetailContent(
                movie = movie,
                onPlay = { onPlay(movie) },
                onBack = onBack
            )
        }
    }
}

@Composable
private fun MovieDetailContent(
    movie: Movie,
    onPlay: () -> Unit,
    onBack: () -> Unit
) {
    val isTelevisionDevice = rememberIsTelevisionDevice()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas)
    ) {
        val compactLayout = !isTelevisionDevice && maxWidth < 900.dp
        val heroHeight = when {
            maxWidth < 700.dp -> 240.dp
            !isTelevisionDevice && maxWidth < 900.dp -> 300.dp
            else -> 440.dp
        }
        val contentPadding = if (compactLayout) {
            PaddingValues(horizontal = 16.dp, vertical = 20.dp)
        } else {
            PaddingValues(horizontal = 56.dp, vertical = 36.dp)
        }
        val posterWidth = if (compactLayout) 148.dp else 240.dp

        AsyncImage(
            model = rememberCrossfadeImageModel(movie.backdropUrl ?: movie.posterUrl),
            contentDescription = movie.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .align(Alignment.TopCenter),
            contentScale = ContentScale.Crop
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, AppColors.HeroTop, AppColors.HeroBottom)
                    )
                )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.colors(
                        containerColor = AppColors.Surface.copy(alpha = 0.72f),
                        contentColor = AppColors.TextPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Outline))
                    )
                ) {
                    Text(stringResource(R.string.movie_detail_back))
                }
            }

            item {
                if (compactLayout) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        MoviePoster(movie = movie, posterWidth = posterWidth)
                        MovieDetailHeroText(movie = movie, onPlay = onPlay)
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        MoviePoster(movie = movie, posterWidth = posterWidth)
                        MovieDetailHeroText(
                            movie = movie,
                            onPlay = onPlay,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoviePoster(
    movie: Movie,
    posterWidth: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .width(posterWidth)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(24.dp))
            .background(AppColors.SurfaceElevated)
    ) {
        AsyncImage(
            model = rememberCrossfadeImageModel(movie.posterUrl ?: movie.backdropUrl),
            contentDescription = movie.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun MovieDetailHeroText(
    movie: Movie,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(label = stringResource(R.string.nav_movies), containerColor = AppColors.BrandMuted)
            movie.rating.takeIf { it > 0f }?.let {
                StatusPill(
                    label = "RTG ${String.format("%.1f", it)}",
                    containerColor = AppColors.Warning,
                    contentColor = Color.Black
                )
            }
            movie.year?.takeIf { it.isNotBlank() }?.let {
                StatusPill(label = it, containerColor = AppColors.SurfaceEmphasis)
            }
        }

        Text(
            text = movie.name,
            style = MaterialTheme.typography.displayMedium,
            color = AppColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        ContentMetadataStrip(
            values = listOf(
                movie.releaseDate.orEmpty(),
                movie.duration.orEmpty(),
                movie.genre.orEmpty()
            )
        )

        MovieFactGrid(movie = movie)

        Button(
            onClick = onPlay,
            colors = ButtonDefaults.colors(
                containerColor = AppColors.Brand,
                contentColor = Color.White
            )
        ) {
            Text(stringResource(R.string.movie_detail_play))
        }

        Text(
            text = movie.plot?.ifBlank { stringResource(R.string.movie_plot_fallback) }
                ?: stringResource(R.string.movie_plot_fallback),
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextSecondary,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MovieFactGrid(movie: Movie) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MovieFactRow(label = stringResource(R.string.movie_detail_director), value = movie.director)
        MovieFactRow(label = stringResource(R.string.movie_detail_release_date), value = movie.releaseDate)
        MovieFactRow(label = stringResource(R.string.movie_detail_duration), value = movie.duration)
        MovieFactRow(label = stringResource(R.string.movie_detail_genre), value = movie.genre)
        MovieFactRow(label = stringResource(R.string.movie_detail_cast), value = movie.cast)
    }
}

@Composable
private fun MovieFactRow(
    label: String,
    value: String?
) {
    val resolvedValue = value?.takeIf { it.isNotBlank() } ?: stringResource(R.string.movie_detail_unknown)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.TextPrimary,
            modifier = Modifier.width(180.dp)
        )
        Text(
            text = resolvedValue,
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
