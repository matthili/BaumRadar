<?xml version="1.0" encoding="UTF-8"?>
<!-- BaumRadar-Baumpunkte: gelbe Marker (App-Look), maßstabsabhängige Größe. -->
<StyledLayerDescriptor version="1.0.0"
    xmlns="http://www.opengis.net/sld"
    xmlns:ogc="http://www.opengis.net/ogc"
    xmlns:xlink="http://www.w3.org/1999/xlink"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.opengis.net/sld http://schemas.opengis.net/sld/1.0.0/StyledLayerDescriptor.xsd">
  <NamedLayer>
    <Name>baumradar_trees</Name>
    <UserStyle>
      <Title>BaumRadar Bäume</Title>
      <FeatureTypeStyle>
        <Rule>
          <Name>weit herausgezoomt</Name>
          <MinScaleDenominator>50000</MinScaleDenominator>
          <PointSymbolizer>
            <Graphic>
              <Mark>
                <WellKnownName>circle</WellKnownName>
                <Fill>
                  <CssParameter name="fill">#FFC107</CssParameter>
                  <CssParameter name="fill-opacity">0.7</CssParameter>
                </Fill>
              </Mark>
              <Size>3</Size>
            </Graphic>
          </PointSymbolizer>
        </Rule>
        <Rule>
          <Name>nah</Name>
          <MaxScaleDenominator>50000</MaxScaleDenominator>
          <PointSymbolizer>
            <Graphic>
              <Mark>
                <WellKnownName>circle</WellKnownName>
                <Fill>
                  <CssParameter name="fill">#FFC107</CssParameter>
                </Fill>
                <Stroke>
                  <CssParameter name="stroke">#2E6B2E</CssParameter>
                  <CssParameter name="stroke-width">1</CssParameter>
                </Stroke>
              </Mark>
              <Size>8</Size>
            </Graphic>
          </PointSymbolizer>
        </Rule>
      </FeatureTypeStyle>
    </UserStyle>
  </NamedLayer>
</StyledLayerDescriptor>
