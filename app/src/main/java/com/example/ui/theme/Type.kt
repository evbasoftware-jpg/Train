package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

val HindSiliguriFontFamily = FontFamily(
  Font(R.font.hind_siliguri, FontWeight.Normal),
  Font(R.font.hind_siliguri, FontWeight.Medium),
  Font(R.font.hind_siliguri, FontWeight.SemiBold),
  Font(R.font.hind_siliguri, FontWeight.Bold),
)

val JetBrainsMonoFontFamily = FontFamily(
  Font(R.font.jetbrains_mono, FontWeight.Normal),
  Font(R.font.jetbrains_mono, FontWeight.Medium),
  Font(R.font.jetbrains_mono, FontWeight.Bold),
)

val Typography = Typography(
  headlineMedium = TextStyle(
    fontFamily = HindSiliguriFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 24.sp,
    lineHeight = 32.sp,
  ),
  headlineSmall = TextStyle(
    fontFamily = HindSiliguriFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 20.sp,
    lineHeight = 28.sp,
  ),
  titleLarge = TextStyle(
    fontFamily = HindSiliguriFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 18.sp,
    lineHeight = 24.sp,
  ),
  titleMedium = TextStyle(
    fontFamily = HindSiliguriFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 22.sp,
  ),
  titleSmall = TextStyle(
    fontFamily = HindSiliguriFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 14.sp,
    lineHeight = 20.sp,
  ),
  bodyLarge = TextStyle(
    fontFamily = HindSiliguriFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 22.sp,
  ),
  bodyMedium = TextStyle(
    fontFamily = HindSiliguriFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp,
  ),
  bodySmall = TextStyle(
    fontFamily = HindSiliguriFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
  ),
  labelLarge = TextStyle(
    fontFamily = HindSiliguriFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 14.sp,
    lineHeight = 20.sp,
  ),
  labelMedium = TextStyle(
    fontFamily = HindSiliguriFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 12.sp,
    lineHeight = 16.sp,
  ),
  labelSmall = TextStyle(
    fontFamily = HindSiliguriFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    lineHeight = 14.sp,
  ),
)
