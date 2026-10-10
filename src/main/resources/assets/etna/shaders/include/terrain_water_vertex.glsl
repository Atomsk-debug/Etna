// Water, for terrain.vsh: which vertices are water (etnaMaterial 3).

bool etnaIsWater(vec4 color) {
    #ifdef ETNA_TRANSLUCENT_LAYER
    // The translucent layer (the classic pass, and Improved Transparency's phases: EtnaShadows.overrideFor):
    // water is the only tinted geometry there (stained glass, ice, slime and honey keep a grey vertex
    // colour, vanilla's face shading being grey too), so any colour in the vertex gives it away, the green
    // of swamp and mangrove water as well as the blue of the sea.
    float peak = max(color.r, max(color.g, color.b));
    float low = min(color.r, min(color.g, color.b));
    return peak - low > 0.04 && color.a > 0.0;
    #else
    // Every other draw (the solid and cutout layers): biome-tinted blue geometry is water (glass and ice
    // are not tinted).
    return color.b > color.r + 0.15 && color.b > color.g && color.a > 0.0;
    #endif
}
