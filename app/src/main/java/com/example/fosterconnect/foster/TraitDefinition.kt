package com.example.fosterconnect.foster

data class TraitDefinition(
    val trait: String,
    val category: String,
    val valence: String,
    val score: Int
)

data class TraitCatalog(
    val traits: List<TraitDefinition>,
    val traitsByCategory: Map<String, List<TraitDefinition>>,
    val conflictMap: Map<String, List<String>>
)
