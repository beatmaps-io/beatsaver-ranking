rootProject.name = "BeatMaps-Scoresaber"

if (File("../beatsaver-common-mp").exists()) {
    includeBuild("../beatsaver-common-mp") {
        dependencySubstitution {
            substitute(module("io.beatmaps:BeatMaps-CommonMP")).using(project(":"))
        }
    }
}
