buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        constraints {
            classpath("com.google.guava:guava:33.8.0-jre") {
                because("AGP still requests a version affected by GHSA-xxph-c9ww-hj94")
            }
            classpath("org.bouncycastle:bcprov-jdk18on:1.87") {
                because("Keep AGP's crypto libraries aligned on the patched Bouncy Castle release")
            }
            classpath("org.bouncycastle:bcpkix-jdk18on:1.87") {
                because("Keep AGP's crypto libraries aligned on the patched Bouncy Castle release")
            }
            classpath("org.bouncycastle:bcutil-jdk18on:1.87") {
                because("Keep AGP's crypto libraries aligned on the patched Bouncy Castle release")
            }
            classpath("org.bitbucket.b_c:jose4j:0.9.7") {
                because("AGP's bundletool requests a version affected by GHSA-3677-xxcr-wjqv")
            }
            classpath("org.jdom:jdom2:2.0.6.1") {
                because("AGP's Jetifier requests a version affected by GHSA-2363-cqg2-863c")
            }
            classpath("org.apache.commons:commons-lang3:3.22.0") {
                because("AGP's commons-compress dependency requests a version affected by GHSA-j288-q9x7-2f5v")
            }
        }
    }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.google.gms.google-services") version "4.6.0" apply false
}
