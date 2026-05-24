Nvidium Continued (NC+)
A community port of Nvidium by MCRcortex, updated to support Sodium 0.5.13 on Minecraft 1.20.1.
What is Nvidium?
Nvidium replaces Sodium's chunk rendering pipeline with a high-performance GPU-based renderer using NVIDIA mesh shaders, offloading chunk rendering from the CPU to the GPU for significantly improved FPS and render distance scaling.
Requirements

Minecraft 1.20.1
Fabric Loader 0.18.4+
Sodium 0.5.13
NVIDIA GPU (GTX 16xx / RTX series or newer)

Installation
Download the latest release from Modrinth and drop it in your mods folder alongside Sodium 0.5.13.
Known Issues

Shader support with Iris may be limited
This is a beta port — bugs are possible
Please report issues on the Issues page

Building from Source
git clone https://github.com/Lord-ChunkOmus/nvidium-continued.git
cd nvidium-continued
git checkout sodium_0.5
.\gradlew build
Credits

Original Nvidium mod by MCRcortex
Port by Lord_ChunkOmus

License
LGPL-3.0 — see LICENSE.txt
