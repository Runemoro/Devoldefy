# Devoldefy
Translates MCP source code to Yarn

## How to use it

Download the [latest release](https://github.com/Runemoro/Devoldefy/releases/latest) and run `java -jar devoldefy.jar`. You'll be asked to enter the following information:
 - The source code's Minecraft version
 - The source code's Forge version (this is used to get a remapped Minecraft jar from your gradle cache folder)
 - The source code's mappings Minecraft version (for 1.12.2, this is 1.12)
 - The source code's mappings' type (snapshot or stable)
 - The source code's mappings' build (the number that follows `snapshot_` or `stable_`)
 - The source code's location (this should be the path to the `src/main/java` folder)
 - The target Minecraft version (1.14)
 - The target Yarn build number (to find out the latest, type `!yv` in the Fabric discord, or look [here](http://maven.modmuss50.me/net/fabricmc/yarn/))
 - The target location (where you want the updated source code to be placed - this must not be a subdirectory of the source location)

Feel free to open an issue or ping me in the Fabric discord if you need help or find a bug with the tool.
