# HiveMQ extensions (downloaded, not committed)

The late-joiner A/B experiment (`LateJoinerExperiment`, ADR-0002) compares a plain HiveMQ CE
broker against a **Sparkplug-aware** one. To run the aware variant:

1. Download a release of the free OSS
   [hivemq-sparkplug-aware-extension](https://github.com/hivemq/hivemq-sparkplug-aware-extension/releases)
   (tested with 4.33.4).
2. Unzip it so the layout is `hivemq-extensions/hivemq-sparkplug-aware-extension/hivemq-extension.xml`.
3. Start the broker with the aware overlay:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.aware.yml up -d --force-recreate
   ```

The overlay mounts only this subdirectory into the container (mounting the whole
`/opt/hivemq/extensions` would clobber the bundled allow-all extension that local dev relies on).
