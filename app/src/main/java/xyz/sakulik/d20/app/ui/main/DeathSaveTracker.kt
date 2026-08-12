package xyz.sakulik.d20.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DeathSaveTracker(
    successes: Int,
    failures: Int,
    isStable: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = when {
                isStable -> "STABLE"
                failures >= 3 -> "DEAD"
                else -> "DYING"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = when {
                isStable -> Color(0xFF4CAF50)
                failures >= 3 -> Color(0xFF8B0000)
                else -> Color(0xFFF44336)
            }
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Successes
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SUCCESSES", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                Row {
                    repeat(3) { index ->
                        SaveSlot(
                            isFilled = index < successes,
                            color = Color(0xFF4CAF50),
                            icon = Icons.Default.Check
                        )
                    }
                }
            }
            
            // Failures
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("FAILURES", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                Row {
                    repeat(3) { index ->
                        SaveSlot(
                            isFilled = index < failures,
                            color = Color(0xFFF44336),
                            icon = Icons.Default.Close
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveSlot(
    isFilled: Boolean,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(24.dp)
            .border(
                width = 1.dp,
                color = if (isFilled) color else Color.Gray.copy(alpha = 0.5f),
                shape = CircleShape
            )
            .clip(CircleShape)
            .background(if (isFilled) color.copy(alpha = 0.2f) else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        if (isFilled) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = color
            )
        }
    }
}
