rootProject.name = "BeatMaps-Beatsaver"

includeBuild("../Common") {
    dependencySubstitution {
        substitute(module("io.beatmaps:Common")).with(project(":"))
    }
}

includeBuild("../CommonMP") {
    dependencySubstitution {
        substitute(module("io.beatmaps:CommonMP")).with(project(":"))
    }
}