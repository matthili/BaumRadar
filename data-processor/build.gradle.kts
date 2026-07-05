plugins {
    application
    id("java")
}

group = "at.mafue.baumradar"
version = "1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}



dependencies {
    // SQLite JDBC Driver
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    
    // BouncyCastle for Ed25519 signature
    implementation("org.bouncycastle:bcprov-jdk15to18:1.77")
    
    // SLF4J Logic and Logback Appender
    implementation("ch.qos.logback:logback-classic:1.5.0")
    
    // JSON processing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")

    // Zstandard-Dekompression für den Photon-Planet-Dump (Geocoder-Cutter).
    // Das JDK kann kein zstd; zstd-jni bündelt die nativen Bibliotheken für alle OS.
    implementation("com.github.luben:zstd-jni:1.5.6-6")

    // JUnit for Testing
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("at.mafue.baumradar.dataprocessor.Main")
}

tasks.withType<Test> {
    useJUnit()
}
