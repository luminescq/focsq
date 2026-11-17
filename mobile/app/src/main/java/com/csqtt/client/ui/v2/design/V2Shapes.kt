package com.csqtt.client.ui.v2.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

@Immutable
object V2Shapes {
    val Field = RoundedCornerShape(14.dp)
    val Row = RoundedCornerShape(14.dp)
    val Card = RoundedCornerShape(16.dp)
    val Hero = RoundedCornerShape(16.dp)
    val Button = RoundedCornerShape(14.dp)
    val Dialog = RoundedCornerShape(22.dp)
    val Pill = RoundedCornerShape(percent = 50)
    val Avatar = RoundedCornerShape(percent = 50)
}

val V2MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = V2Shapes.Row,
    large = V2Shapes.Card,
    extraLarge = V2Shapes.Dialog,
)
