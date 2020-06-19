package net.minestom.converter;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.*;
import net.minestom.server.instance.batch.ChunkBatch;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.RegistryBlock;
import net.minestom.server.registry.RegistryMain;
import net.minestom.server.storage.StorageFolder;
import net.minestom.server.storage.systems.FileStorageSystem;
import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.Tag;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public class StandaloneConverter {

	public File baseDir;

	public HashMap<String, MCAFile> mcaFiles = new HashMap<>();

	public HashMap<String, UseableRegistryBlock> blocks = new HashMap<>();

	public static void main(String[] args) {
		try {
			new StandaloneConverter().run(args);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void run(String[] args) throws Exception {
		OptionParser parser = new OptionParser();
		OptionSpec<String> option = parser.accepts("range", "Radius to convert").withRequiredArg().required();
		OptionSet parse = parser.parse(args);
		int radius = Integer.parseInt(parse.valueOf(option));
		baseDir = new File("regions");
		if (baseDir.isDirectory()) {
			for (final File file : baseDir.listFiles()) {
				if (file.getName().startsWith("r.") && file.getName().endsWith(".mca")) {
					mcaFiles.put(file.getName(), MCAUtil.read(file));
				}
			}
		} else {
			System.out.println("there needs to be a folder named regions in this directory");
			System.exit(-1);
		}

		MinecraftServer.init();

		MinecraftServer.getStorageManager().defineDefaultStorageSystem(FileStorageSystem::new);

		StorageFolder chunks = MinecraftServer.getStorageManager().getFolder("chunks");

		// Create the instance
		InstanceContainer instanceContainer = MinecraftServer.getInstanceManager().createInstanceContainer(chunks);
		// Set the ChunkGenerator
		instanceContainer.setChunkGenerator(new ConvertGen());
		// Enable the auto chunk loading (when players come close)
		instanceContainer.enableAutoChunkLoad(true);

		Method parseBlocks = RegistryMain.class.getDeclaredMethod("parseBlocks", String.class);
		parseBlocks.setAccessible(true);
		((List<RegistryBlock>) parseBlocks.invoke(null, RegistryMain.BLOCKS_PATH)).forEach(rblock -> {
			UseableRegistryBlock rblock2 = new UseableRegistryBlock(rblock);
			blocks.put(rblock2.name, rblock2);
		});

		int loopStart = -radius;
		int loopEnd = radius;
		for (int x = loopStart; x < loopEnd; x++)
			for (int z = loopStart; z < loopEnd - 1; z++)
				instanceContainer.loadChunk(x, z, chunk -> {
				});

		instanceContainer.loadChunk(loopEnd, loopEnd, chunk -> {
			System.out.println("saving");
			instanceContainer.saveChunksToStorageFolder(() -> {
				System.out.println("done");
				System.exit(0);
			});
		});
	}

	private class ConvertGen extends ChunkGenerator {

		@Override
		public void generateChunkData(ChunkBatch batch, int chunkX, int chunkZ) {
			MCAFile mcaFile = mcaFiles.get(MCAUtil.createNameFromChunkLocation(chunkX, chunkZ));
			if (mcaFile != null) {
				net.querz.mca.Chunk chunk = mcaFile.getChunk(chunkX, chunkZ);
				if (chunk != null) {
					System.out.println("Copying Chunk: " + chunkX + ", " + chunkZ);
					for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++)
						for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
							for (int y = 0; y < Chunk.CHUNK_SIZE_Y; y++) {
								try {
									CompoundTag blockState = chunk.getBlockStateAt(x, y, z);
									if (blockState == null) {
										continue;
									}
									String name = blockState.get("Name").valueToString().substring(11).replace("\"", "").toUpperCase();
									UseableRegistryBlock rBlock = blocks.get(name);
									if (rBlock == null) {
										System.out.println("rblock is null, name: " + name);
										continue;
									}
									if (blockState.getCompoundTag("Properties") != null) {
										CompoundTag properties = blockState.getCompoundTag("Properties");
										List<String> propertiesArray = new ArrayList<>();
										properties.forEach((key, value2) -> {
											Tag<String> value = (Tag<String>) value2;
											propertiesArray.add(key + "=" + value.valueToString().replace("\"", ""));
										});
										Collections.sort(propertiesArray);
										short block = Block.fromId(rBlock.defaultId).withProperties(propertiesArray.toArray(new String[0]));
										batch.setBlock(x, y, z, block);
									} else
										batch.setBlock(x, y, z, rBlock.defaultId);
								} catch (NullPointerException ignored) {
								} catch (Exception e) {
									e.printStackTrace();
								}
							}
						}
				} else {
					//idk if i need this just want to make sure another generator doesn't try to regenerate this chunk
					batch.setBlock(0, 0, 0, (short) 0);
					System.out.println("No data for chunk: " + chunkX + ", " + chunkZ);
				}
			} else {
				//idk if i need this just want to make sure another generator doesn't try to regenerate this chunk
				batch.setBlock(0, 0, 0, (short) 0);
				System.out.println("No data for chunk: " + chunkX + ", " + chunkZ);
			}
		}

		@Override
		public void fillBiomes(Biome[] biomes, int chunkX, int chunkZ) {
			MCAFile mcaFile = mcaFiles.get(MCAUtil.createNameFromChunkLocation(chunkX, chunkZ));
			if (mcaFile != null && mcaFile.getChunk(chunkX, chunkZ) != null) {
				net.querz.mca.Chunk chunk = mcaFile.getChunk(chunkX, chunkZ);

				int[] biomesArray = chunk.getBiomes();

				for (int i = 0; i < biomesArray.length; i++) {
					biomes[i] = Biome.fromId(biomesArray[i]);
				}
			} else
				Arrays.fill(biomes, Biome.VOID);
		}

		@Override
		public List<ChunkPopulator> getPopulators() {
			return null;
		}

	}

	public static class UseableRegistryBlock {

		public String name;

		public List<String> propertiesIdentifiers = new ArrayList<>();

		public List<String> defaultPropertiesValues = new ArrayList<>();
		public short defaultId;

		public List<RegistryBlock.BlockState> states = new ArrayList<>();

		public UseableRegistryBlock(RegistryBlock b) {
			try {
				Class<RegistryBlock> registryBlockClass = RegistryBlock.class;
				Field nameField = registryBlockClass.getDeclaredField("name");
				Field propertiesIdentifiersField = registryBlockClass.getDeclaredField("propertiesIdentifiers");
				Field defaultPropertiesValuesField = registryBlockClass.getDeclaredField("defaultPropertiesValues");
				Field defaultIdField = registryBlockClass.getDeclaredField("defaultId");
				Field statesField = registryBlockClass.getDeclaredField("states");
				nameField.setAccessible(true);
				propertiesIdentifiersField.setAccessible(true);
				defaultPropertiesValuesField.setAccessible(true);
				defaultIdField.setAccessible(true);
				statesField.setAccessible(true);
				this.name = (String) nameField.get(b);
				this.propertiesIdentifiers = (List<String>) propertiesIdentifiersField.get(b);
				this.defaultPropertiesValues = (List<String>) defaultPropertiesValuesField.get(b);
				this.defaultId = (short) defaultIdField.get(b);
				this.states = (List<RegistryBlock.BlockState>) statesField.get(b);
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}

	}

}
