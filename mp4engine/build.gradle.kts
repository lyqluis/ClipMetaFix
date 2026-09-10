plugins { kotlin("jvm") version "2.1.0" }

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    useJUnit()
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
