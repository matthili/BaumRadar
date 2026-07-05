# Baut den "Insel"-Graph: extrahiert die 19 Stadt-BBoxen aus den Länder-PBFs
# und merged sie zu einem kleinen PBF für GraphHopper. Nur JDK-freie Bordmittel:
# osmium-tool (Extract/Merge), jq (Katalog lesen), curl (PBFs laden).
FROM debian:bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends osmium-tool jq curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

COPY build-island.sh /usr/local/bin/build-island.sh
# CRLF-Schutz: Windows-Checkouts (core.autocrlf) machen aus "bash" -> "bash\r"
# und der Container stirbt mit exit 127 — deshalb Zeilenenden hier normalisieren.
RUN sed -i 's/\r$//' /usr/local/bin/build-island.sh \
 && chmod +x /usr/local/bin/build-island.sh

ENTRYPOINT ["/usr/local/bin/build-island.sh"]
