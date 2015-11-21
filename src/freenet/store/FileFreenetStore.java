package freenet.store;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicLong;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;

import freenet.keys.KeyVerifyException;
import freenet.node.stats.StoreAccessStats;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.Logger;
import freenet.support.Ticker;

// Might want to use the first ~20 bits of CRC of routing key for quasi bloom filter? java.util.zip.CRC32

/**
 */
public class FileFreenetStore<T extends StorableBlock> implements FreenetStore<T> {

    private static final byte[] EMPTY = new byte[0];
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final Path basePath;

	private final class Block {
	
		final byte[] header;
		final byte[] data;
		final byte[] fullKey;
		boolean oldBlock;
		
		Block() {
		    this.header = new byte[callback.headerLength()];
		    this.data = new byte[callback.dataLength()];
		    this.fullKey = callback.storeFullKeys() ? new byte[callback.fullKeyLength()] : EMPTY;
		}
		
		Block(byte[] header, byte[] data, byte[] fullKey) {
		    this(header, data, fullKey, false);
		}
		
		Block(byte[] header, byte[] data, byte[] fullKey, boolean oldBlock) {
		    this.header = header;
		    this.data = data;
		    this.fullKey = callback.storeFullKeys() ? fullKey : EMPTY;
		    this.oldBlock = oldBlock;
		}
	}
	
	private final StoreCallback<T> callback;
	
	private long maxKeys;
	private final AtomicLong hits;
	private final AtomicLong misses;
	private final AtomicLong writes;
	private final AtomicLong count;
	
	public FileFreenetStore(StoreCallback<T> callback, File baseDir, String name, long maxKeys) {
	    this.basePath = baseDir.toPath().resolve("datastore-files").resolve(name);
	    try {
	        Files.createDirectories(this.basePath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
	    
	    this.hits = new AtomicLong(0);
	    this.misses = new AtomicLong(0);
	    this.writes = new AtomicLong(0);
	    this.count = new AtomicLong(countFiles());
	    
		this.callback = callback;
		this.maxKeys = maxKeys;
		callback.setStore(this);
	}
	
	private long countFiles() {
	    DirectoryStream<Path> dats = null;
	    try {
	        dats = Files.newDirectoryStream(basePath, "*/*/*.dat");
	        long count = 0;
	        for (Path dat : dats) {
	            count++;
	        }
	        return count;
        } catch (IOException e) {
            e.printStackTrace();
            return 0;
        } finally {
            try {
                if (dats != null) {
                    dats.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
	}
	
	private Path pathFromRoutingKey(byte[] routingKey) {
	    char[] pathChars = new char[2 * routingKey.length + 6];
	    int j = 0;
	    for (int i = 0; i < routingKey.length; i++) {
	        int b = routingKey[i] & 0xFF;
	        pathChars[j++] = HEX[b >> 4];
	        pathChars[j++] = HEX[b & 0x0F];
	        if (i <= 1) {
                pathChars[j++] = '/';
	        }
	    }
        pathChars[j++] = '.';
        pathChars[j++] = 'd';
        pathChars[j++] = 'a';
        pathChars[j++] = 't';
	    return basePath.resolve(new String(pathChars));
	}
	
	private Block fetchBlock(Path path) {
	    InputStream is = null;
	    try {
            is = Files.newInputStream(path);
            Block b = new Block();
            is.read(b.header);
            is.read(b.data);
            is.read(b.fullKey);
            b.oldBlock = is.read() != 0;
            return b;
        } catch(IOException e) {
            // Can be anything, but probably the block didn't exist.
            // Anyways, we can't access the block, so just return null.
            return null;
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
	}
	
	private void putBlock(Path path, Block b) {
	    OutputStream os = null;
	    try {
	        Files.createDirectories(path.getParent());
            os = Files.newOutputStream(path);
            os.write(b.header);
            os.write(b.data);
            os.write(b.fullKey);
            os.write(b.oldBlock ? (byte)0x01 : (byte)0x00);
            return;
        } catch (IOException e) {
            e.printStackTrace();
            return;
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
	}
	
	private void removeBlock(Path path) {
	    try {
            if (Files.deleteIfExists(path)) {
                count.decrementAndGet();
            } else {
                System.err.println("Race condition: removing non-existent block " + path);
            }
            return;
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
	}
	
	@Override
	public T fetch(byte[] routingKey, byte[] fullKey,
			boolean dontPromote, boolean canReadClientCache, boolean canReadSlashdotCache, boolean ignoreOldBlocks, BlockMetadata meta) throws IOException {
		Path path = pathFromRoutingKey(routingKey);
		Block block = fetchBlock(path);
		if(block == null) {
			misses.incrementAndGet();
			return null;
		}
		if(ignoreOldBlocks && block.oldBlock) {
			return null;
		}
		try {
			T ret =
				callback.construct(block.data, block.header, routingKey, block.fullKey, canReadClientCache, canReadSlashdotCache, meta, null);
			hits.incrementAndGet();
			// TODO:
			//if(!dontPromote)
			//	blocksByRoutingKey.push(key, block);
			if(meta != null && block.oldBlock)
				meta.setOldBlock();
			return ret;
		} catch (KeyVerifyException e) {
			removeBlock(path);
			count.decrementAndGet();
			misses.incrementAndGet();
			return null;
		}
	}

	@Override
	public synchronized long getMaxKeys() {
		return maxKeys;
	}

	@Override
	public long hits() {
		return hits.get();
	}

	@Override
	public long keyCount() {
		return count.get();
	}

	@Override
	public long misses() {
		return misses.get();
	}

	@Override
	public void put(T block, byte[] data, byte[] header, boolean overwrite, boolean isOldBlock) throws KeyCollisionException {
		byte[] routingKey = block.getRoutingKey();
		byte[] fullKey = block.getFullKey();
		
		writes.incrementAndGet();
		Path path = pathFromRoutingKey(routingKey);
		Block oldBlock = fetchBlock(path);
		
		if(oldBlock != null) {
			if(callback.collisionPossible()) {
				if (Arrays.equals(oldBlock.data, data) &&
					    Arrays.equals(oldBlock.header, header) &&
					    (!callback.storeFullKeys() || Arrays.equals(oldBlock.fullKey, fullKey))) {
				    // Whatever. Just overwrite the thing already.
				    // TODO: optimization: check for oldBlock == isOldBlock
				}
				else if (!overwrite) {
					throw new KeyCollisionException();
				}
			}
		} else {
		    count.incrementAndGet();
		}
		Block storeBlock = new Block(header, data, fullKey, isOldBlock);
		putBlock(path, storeBlock);
		// TODO
		/*
		while(blocksByRoutingKey.size() > maxKeys) {
			blocksByRoutingKey.popKey();
		}
		*/
	}

	@Override
	public synchronized void setMaxKeys(long maxStoreKeys, boolean shrinkNow)
			throws IOException {
		this.maxKeys = maxStoreKeys;
	    // TODO
		/*
		this.maxKeys = (int)Math.min(Integer.MAX_VALUE, maxStoreKeys);
		// Always shrink now regardless of parameter as we will shrink on the next put() anyway.
		while(blocksByRoutingKey.size() > maxKeys) {
			blocksByRoutingKey.popKey();
		}
		*/
	}

	@Override
	public long writes() {
		return writes.get();
	}

	@Override
	public long getBloomFalsePositive() {
		return -1;
	}
	
	@Override
	public boolean probablyInStore(byte[] routingKey) {
	    /*
		ByteArrayWrapper key = new ByteArrayWrapper(routingKey);
		return blocksByRoutingKey.get(key) != null;
	    */
	    return true;
	}

	public void clear() {
		//blocksByRoutingKey.clear();
		// TODO
	}

	public void migrateTo(StoreCallback<T> target, boolean canReadClientCache) throws IOException {
	    // TODO
	    /*
		Enumeration<ByteArrayWrapper> keys = blocksByRoutingKey.keys();
		while(keys.hasMoreElements()) {
			ByteArrayWrapper routingKeyWrapped = keys.nextElement();
			byte[] routingKey = routingKeyWrapped.get();
			Block block = blocksByRoutingKey.get(routingKeyWrapped);
			
			T ret;
			try {
				ret = callback.construct(block.data, block.header, routingKey, block.fullKey, canReadClientCache, false, null, null);
			} catch (KeyVerifyException e) {
				Logger.error(this, "Caught while migrating: "+e, e);
				continue;
			}
			try {
				target.getStore().put(ret, block.data, block.header, false, block.oldBlock);
			} catch (KeyCollisionException e) {
				// Ignore
			}
		}
		*/
	}
	
	@Override
	public StoreAccessStats getSessionAccessStats() {
		return new StoreAccessStats() {

			@Override
			public long hits() {
				return hits.get();
			}

			@Override
			public long misses() {
				return misses.get();
			}

			@Override
			public long falsePos() {
				return 0;
			}

			@Override
			public long writes() {
				return writes.get();
			}
			
		};
	}

	@Override
	public StoreAccessStats getTotalAccessStats() {
		return null;
	}

	@Override
	public boolean start(Ticker ticker, boolean longStart) throws IOException {
		return false;
	}

	@Override
	public void setUserAlertManager(UserAlertManager userAlertManager) {
		// Do nothing
	}
	
	@Override
	public FreenetStore<T> getUnderlyingStore() {
		return this;
	}
	
	@Override
	public void close() {
		// Do nothing
	}
}
