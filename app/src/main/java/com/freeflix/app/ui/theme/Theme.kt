package com.freeflix.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ──────────────────────────────────────────────────────────────────── //
//  Netflix-accurate colour palette                                    //
//  Hex values mirror the real Netflix web/app surfaces.               //
// ──────────────────────────────────────────────────────────────────── //

/** Netflix signature red — the brand N logo + primary accent. */
val Red = Color(0xFFE50914)
/** Netflix deep red — pressed / gradient end. */
val DarkRed = Color(0xFFB20710)

/** App background — true near-black like Netflix home. */
val Bg = Color(0xFF141414)
/** Card / surface background — slightly lifted from Bg. */
val Bg2 = Color(0xFF1A1A1A)
/** Elevated surface (hover row backgrounds, sheets). */
val Bg3 = Color(0xFF2F2F2F)
/** Netflix "grey button" surface (More Info / secondary). */
val GreyButton = Color(0xFF808080)
val GreyButtonHover = Color(0xFFA6A6A6)

/** Primary text — Netflix off-white. */
val TextPrimary = Color(0xFFE5E5E5)
/** Secondary / muted text — Netflix grey. */
val TextMuted = Color(0xFFB3B3B3)
/** Netflix's yellow rating accent (TMDB gold). */
val Gold = Color(0xFFF5C518)

/** Pure black for the player / immersive surfaces. */
val PureBlack = Color(0xFF000000)
/** Netflix "Play" button white. */
val PlayWhite = Color(0xFFFFFFFF)

private val DarkScheme = darkColorScheme(
    primary = Red,
    onPrimary = Color.White,
    secondary = Gold,
    background = Bg,
    surface = Bg2,
    surfaceVariant = Bg3,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = Red,
)

// ──────────────────────────────────────────────────────────────────── //
//  Typography — Netflix uses tight, bold, condensed-feeling sans.     //
//  Compose has no built-in condensed family, so we lean on weight +   //
//  letter spacing to evoke the Netflix look.                          //
// ──────────────────────────────────────────────────────────────────── //
private val NetflixTypography = Typography(
    displayLarge = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.0).sp),
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun NetflixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = NetflixTypography,
        content = content
    )
}

// ──────────────────────────────────────────────────────────────────── //
//  Netflix-style animation specs                                      //
//  Centralised so every screen animates with the same feel.           //
// ──────────────────────────────────────────────────────────────────── //

/** Netflix uses an expressive "emphasized" easing — a snappy decelerate. */
val NetflixEmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
val NetflixStandardEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

/** Card scale-up on focus — the signature Netflix "card grows" hover. */
fun <T> netflixCardScaleSpec(): SpringSpec<T> = spring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** Card lift / elevation on focus. */
fun <T> netflixCardLiftSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = 280,
    easing = NetflixEmphasizedEasing,
)

/** Hero crossfade between featured titles. */
fun <T> netflixCrossfadeSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = 900,
    easing = FastOutSlowInEasing,
)

/** Nav-bar fade (transparent → solid black) on scroll. */
fun <T> netflixNavFadeSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = 350,
    easing = NetflixStandardEasing,
)

/** Row content slide-in. */
fun <T> netflixRowSlideSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = 450,
    easing = NetflixEmphasizedEasing,
)

/** Sheet / overlay slide-up. */
fun <T> netflixSheetSlideSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = 380,
    easing = NetflixEmphasizedEasing,
)

/** Fast fade for buttons / chips. */
fun <T> netflixFastFadeSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = 180,
    easing = NetflixStandardEasing,
)
