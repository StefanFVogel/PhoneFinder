plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
}
