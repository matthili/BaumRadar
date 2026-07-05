# Dünnes GraphHopper-Image: offizielles Web-Fat-JAR aus dem GitHub-Release +
# unsere Config. Kein Fork — die Zonen-Vermeidung kommt als Custom-Model pro
# Anfrage vom Client. Version über Build-Arg pinnbar.
FROM eclipse-temurin:21-jre

ARG GH_VERSION=10.0
WORKDIR /gh

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fSL "https://github.com/graphhopper/graphhopper/releases/download/${GH_VERSION}/graphhopper-web-${GH_VERSION}.jar" \
      -o graphhopper-web.jar

COPY config.yml /gh/config.yml
COPY entrypoint.sh /gh/entrypoint.sh
# CRLF-Schutz: Windows-Checkouts (core.autocrlf) brechen den Shebang (exit 127).
RUN sed -i 's/\r$//' /gh/entrypoint.sh \
 && chmod +x /gh/entrypoint.sh

EXPOSE 8989
ENTRYPOINT ["/gh/entrypoint.sh"]
