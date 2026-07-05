# Photon-Geocoder (Adress- & Ortssuche) für die BaumRadar-Städte.
# Importiert beim ersten Start die pro-Stadt-Geocoder-Häppchen aus dem
# BaumRadar-Katalog (GitHub Pages, je Stadt 15–176 MB statt 11-GB-Länder-Dumps)
# und bedient danach /api (Suche) + /reverse — komplett lokal.
FROM eclipse-temurin:21-jre

ARG PHOTON_VERSION=1.2.1
WORKDIR /photon-app

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates jq \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fSL "https://github.com/komoot/photon/releases/download/${PHOTON_VERSION}/photon-${PHOTON_VERSION}.jar" \
      -o photon.jar

COPY entrypoint.sh /photon-app/entrypoint.sh
# CRLF-Schutz: Windows-Checkouts (core.autocrlf) brechen den Shebang (exit 127).
RUN sed -i 's/\r$//' /photon-app/entrypoint.sh \
 && chmod +x /photon-app/entrypoint.sh

EXPOSE 2322
ENTRYPOINT ["/photon-app/entrypoint.sh"]
