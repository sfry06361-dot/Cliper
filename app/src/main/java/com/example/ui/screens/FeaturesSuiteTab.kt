package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.EditingFeatureId
import com.example.model.FeatureCategory
import com.example.ui.StudioUiState
import com.example.ui.components.StudioSectionHeader
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ViralAmber
import java.util.Locale

@Composable
fun Features40SuiteTab(
    uiState: StudioUiState,
    onToggleFeature: (EditingFeatureId) -> Unit,
    onActivateProBundle: () -> Unit
) {
    var selectedCategory by rememberSaveable { mutableStateOf<FeatureCategory?>(null) }
    val activeSet = uiState.activeFeatures
    val displayedFeatures = if (selectedCategory == null) {
        EditingFeatureId.entries
    } else {
        EditingFeatureId.entries.filter { it.category == selectedCategory }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            StudioSectionHeader(
                                title = "40 ميزة احترافية تتفعل مع الفيديو",
                                subtitle = "تشمل المونتاج، اللقطات المهمة، الشرح، والترقية البصرية",
                                icon = Icons.Rounded.AutoFixHigh,
                                accent = ElectricCyan
                            )
                        }
                        Surface(
                            color = EmeraldContainer,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "${activeSet.size} / 40",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = EmeraldSuccess,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { (activeSet.size / 40f).coerceIn(0f, 1f) },
                        color = ElectricCyan,
                        trackColor = StudioBorder,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )

                    Button(
                        onClick = onActivateProBundle,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ViralAmber,
                            contentColor = ObsidianBg
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("activate_pro_bundle_button")
                    ) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "تفعيل باقة المونتاج والشرح واللقطات الشاملة",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("الكل (40)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanContainer,
                        selectedLabelColor = ElectricCyan
                    )
                )
                FeatureCategory.entries.forEach { cat ->
                    val activeInCat = EditingFeatureId.entries.count { it.category == cat && it in activeSet }
                    val totalInCat = EditingFeatureId.entries.count { it.category == cat }
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text("${cat.labelAr} ($activeInCat/$totalInCat)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberContainer,
                            selectedLabelColor = ViralAmber
                        )
                    )
                }
            }
        }

        items(displayedFeatures, key = { it.name }) { feature ->
            val isActive = feature in activeSet
            Card(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.dp,
                    if (isActive) ElectricCyan.copy(alpha = 0.65f) else StudioBorder
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) StudioSurfaceElevated else StudioSurface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleFeature(feature) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = if (isActive) CyanContainer else ObsidianBg,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isActive) ElectricCyan.copy(alpha = 0.6f) else StudioBorder
                        )
                    ) {
                        Text(
                            text = String.format(Locale.US, "#%02d", feature.number),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = if (isActive) ElectricCyan else TextSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = feature.titleAr,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isActive) TextPrimary else TextPrimary.copy(alpha = 0.85f)
                        )
                        Text(
                            text = feature.subtitleAr,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = feature.category.labelAr,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActive) ViralAmber else TextSecondary.copy(alpha = 0.7f)
                        )
                    }

                    Switch(
                        checked = isActive,
                        onCheckedChange = { onToggleFeature(feature) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ObsidianBg,
                            checkedTrackColor = ElectricCyan
                        ),
                        modifier = Modifier.testTag("feature_switch_${feature.number}")
                    )
                }
            }
        }
    }
}
