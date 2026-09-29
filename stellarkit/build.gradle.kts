import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("maven-publish")
}

val roomVersion = "2.8.4"

// The Room 2.6.1 fixture's expected contents, shared by the host, desktop and device tests.
val sharedFixtureDir = "src/test/sharedFixture"

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // 21, not 17: sqlcipher-room-jvm and sqlcipher-driver publish Java 21 bytecode only.
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        // Not commonMain: the kit is JVM code shared by the two JVM-backed targets only.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(libs.stellar.sdk)
                // Public API exposes okhttp3.EventListener.Factory (getInstance overloads).
                api("com.squareup.okhttp3:okhttp:4.12.0")
                implementation(libs.kotlinx.coroutines.core)
                implementation("co.touchlab:kermit:2.1.0")
                implementation("androidx.room:room-runtime:$roomVersion")
            }
        }
        androidMain {
            // Sources come from AGP's own `main` source set; adding them here too would
            // list the same file in two fragments.
            dependsOn(jvmCommonMain)
            dependencies {
                implementation("androidx.core:core-ktx:1.16.0")
                implementation("androidx.appcompat:appcompat:1.7.0")
                implementation("com.google.android.material:material:1.12.0")
                implementation("androidx.room:room-ktx:$roomVersion")
                // Public API exposes its exceptions and DatabaseMigrationResult.
                api("com.github.piratecash.bitcoin-kit-android:sqlcipher-room:v0.1.0-pcash.36")
            }
        }
        val desktopMain by getting {
            kotlin.srcDir("src/main/java")
            dependsOn(jvmCommonMain)
            dependencies {
                api("com.github.piratecash.bitcoin-kit-android:sqlcipher-room:v0.1.0-pcash.36")
            }
        }

        val androidUnitTest by getting {
            kotlin.srcDir(sharedFixtureDir)
            dependencies {
                implementation("junit:junit:4.13.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")
                implementation("androidx.test:core:1.5.0")
                implementation("org.robolectric:robolectric:4.11.1")
                implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
            }
        }
        // Runs on a device only; never part of the published AAR.
        val androidInstrumentedTest by getting {
            kotlin.srcDir(sharedFixtureDir)
            dependencies {
                implementation("androidx.test.ext:junit:1.2.1")
                implementation("androidx.test.espresso:espresso-core:3.6.1")
            }
        }
        val desktopTest by getting {
            kotlin.srcDir(sharedFixtureDir)
            resources.srcDir("src/test/resources")
            dependencies {
                implementation("junit:junit:4.13.2")
                // Plaintext fixtures only; the kit itself opens databases through SQLCipher.
                implementation("androidx.sqlite:sqlite-bundled:2.6.2")
                // Reads and stages encrypted files directly; aligned with sqlcipher-room.
                implementation("com.github.piratecash.bitcoin-kit-android:sqlcipher-driver:v0.1.0-pcash.36")
            }
        }
    }
}

dependencies {
    add("kspAndroid", "androidx.room:room-compiler:$roomVersion")
    add("kspDesktop", "androidx.room:room-compiler:$roomVersion")
    // KMP naming trap: kspAndroidTest is the JVM unit-test source set. The test-only @Database in
    // KitDatabaseMigrationTest needs its own _Impl.
    add("kspAndroidTest", "androidx.room:room-compiler:$roomVersion")
}

android {
    namespace = "io.horizontalsystems.stellarkit"
    compileSdk = 35

    sourceSets {
        // The Room 2.6.1 fixture; read-only, shared with the host tests.
        getByName("androidTest").resources.srcDir("src/test/resources")
    }

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP's "test" lifecycle task only aggregates Android unit tests; wire in the desktop target too.
afterEvaluate {
    tasks.named("test") {
        dependsOn("desktopTest")
    }
}
