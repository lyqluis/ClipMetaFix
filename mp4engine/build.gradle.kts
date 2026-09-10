plugins { kotlin("jvm") version "2.1.0" }

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    useJUnit()
    timeout.set(java.time.Duration.ofMinutes(5))   // 整个任务 5 分钟兜底
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
