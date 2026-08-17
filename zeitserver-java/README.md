# zeitserver-java

Maven-Modul der Projektarbeit: Zeitquellen **NTP**, **GPS**, **DCF77**, Fusion, Web-UI.

Dokumentation: [../docs/SOFTWARE.md](../docs/SOFTWARE.md) · Deploy: [../README.md](../README.md)

```bash
mvn -q package -DskipTests
java -jar target/zeitserver-1.0-SNAPSHOT-all.jar once
java -jar target/zeitserver-1.0-SNAPSHOT-all.jar web
```

Konfiguration: `src/main/resources/config.properties` (Entwicklung), `config.pi.properties` (Raspberry Pi).
