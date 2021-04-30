package net.minestom.converter;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkGenerator;
import net.minestom.server.instance.ChunkPopulator;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.batch.ChunkBatch;
import net.minestom.server.instance.block.Block;
import net.minestom.server.storage.StorageLocation;
import net.minestom.server.storage.systems.FileStorageSystem;
import net.minestom.server.utils.time.TimeUnit;
import net.minestom.server.world.biomes.Biome;
import net.minestom.server.world.biomes.BiomeManager;
import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.Tag;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public class StandaloneConverter {

	public File baseDir;

	public HashMap<String, MCAFile> mcaFiles = new HashMap<>();
	public HashMap<String, Block> blocks = new HashMap<>();
	InstanceContainer instanceContainer;

	public static void main(String[] args) {
		try {
			new StandaloneConverter().run(args);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void run(String[] args) throws Exception {
		baseDir = new File("regions");
		if (baseDir.isDirectory()) {
			for (final File file : baseDir.listFiles()) {
				if (file.length() > 0 &&
						file.getName().startsWith("r.") && file.getName().endsWith(".mca")) {
					mcaFiles.put(file.getName(), MCAUtil.read(file));
				}
			}
		} else {
			System.out.println("there needs to be a folder named regions in this directory");
			System.exit(-1);
		}

		MinecraftServer.init();

		MinecraftServer.getStorageManager().defineDefaultStorageSystem(FileStorageSystem::new);

		StorageLocation storage = MinecraftServer.getStorageManager().getLocation("chunks");

		// Create the instance
		instanceContainer = MinecraftServer.getInstanceManager().createInstanceContainer(storage);
		// Set the ChunkGenerator
		instanceContainer.setChunkGenerator(new ConvertGen());
		// Enable the auto chunk loading (when players come close)
		instanceContainer.enableAutoChunkLoad(true);

		for (final Block block : Block.values()) {
			blocks.put(block.getName(), block);
		}

		CountDownLatch countDownLatch = new CountDownLatch((mcaFiles.size()) * 1024);
		for (final MCAFile file : mcaFiles.values()) {
			int rX;
			int rZ;
			final Class<? extends MCAFile> fileClass = file.getClass();
			final Field regionX = fileClass.getDeclaredField("regionX");
			final Field regionZ = fileClass.getDeclaredField("regionZ");
			regionX.setAccessible(true);
			regionZ.setAccessible(true);
			rX = (int) regionX.get(file);
			rZ = (int) regionZ.get(file);
			for (int x = rX * 32; x < (rX * 32) + 32; x++)
				for (int z = rZ * 32; z < (rZ * 32) + 32; z++)
					instanceContainer.loadChunk(x, z, chunk -> countDownLatch.countDown());
		}
		MinecraftServer.getSchedulerManager().buildTask(()->instanceContainer.tick(0)).repeat(1, TimeUnit.TICK).schedule();
		countDownLatch.await();
		System.out.println("saving");
		instanceContainer.saveChunksToStorage(() -> {
			System.out.println("done");
			System.exit(0);
		});
		MinecraftServer.getSchedulerManager().shutdown();
	}

	private class ConvertGen implements ChunkGenerator {

		BiomeManager bm = MinecraftServer.getBiomeManager();

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
									String name = blockState.get("Name").valueToString().replace("\"", "");
									Block block = blocks.get(name);
									if (block == null) {
										System.out.println("block is null, name: " + name);
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
										short blockStateId = block.withProperties(propertiesArray.toArray(new String[0]));
										batch.setBlockStateId(x, y, z, blockStateId);
									} else
										batch.setBlock(x, y, z, block);
								} catch (NullPointerException ignored) {
								} catch (Exception e) {
									e.printStackTrace();
								}
							}
						}
				}
			}
		}

		@Override
		public void fillBiomes(Biome[] biomes, int chunkX, int chunkZ) {
			MCAFile mcaFile = mcaFiles.get(MCAUtil.createNameFromChunkLocation(chunkX, chunkZ));
			Arrays.fill(biomes, Biome.PLAINS);
			if (mcaFile != null && mcaFile.getChunk(chunkX, chunkZ) != null) {
				net.querz.mca.Chunk chunk = mcaFile.getChunk(chunkX, chunkZ);

				int[] biomesArray = chunk.getBiomes();

				for (int i = 0; i < biomesArray.length; i++) {
					final Biome biome = bm.getById(biomesArray[i]);
					if (biome == null) {
						continue;
					}
					biomes[i] = biome;
				}
			}
		}

		@Override
		public List<ChunkPopulator> getPopulators() {
			return null;
		}

	}

}
