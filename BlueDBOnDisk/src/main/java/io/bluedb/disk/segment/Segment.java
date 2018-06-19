package io.bluedb.disk.segment;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.nustaq.serialization.FSTConfiguration;
import io.bluedb.api.exceptions.BlueDbException;
import io.bluedb.api.keys.BlueKey;
import io.bluedb.disk.Blutils;

public class Segment {
	private static String SUFFIX = ".segment";

	final String pathString;
	final Path path;
	private static final FSTConfiguration serializer = FSTConfiguration.createDefaultConfiguration();

	public Segment(Path collectionPath, String segmentId) {
		this.path = Paths.get(collectionPath.toString(), segmentId + SUFFIX);
		this.pathString = this.path.toString();
	}

	public Segment(Path collectionPath, long segmentId) {
		this.path = Paths.get(collectionPath.toString(), segmentId + SUFFIX);
		this.pathString = this.path.toString();
	}

	public void put(BlueKey key, Serializable value) throws BlueDbException {
		TreeMap<BlueKey, BlueEntity> data = load();
		BlueEntity entity = new BlueEntity(key, value);
		data.put(key, entity);
		save(data);
	}

	public void delete(BlueKey key) throws BlueDbException {
		TreeMap<BlueKey, BlueEntity> data = load();
		data.remove(key);
		save(data);
	}

	public List<BlueEntity> read() throws BlueDbException {
		List<BlueEntity> results = new ArrayList<>(load().values());
		return results;
	}

	@SuppressWarnings("unchecked")
	private TreeMap<BlueKey, BlueEntity> load() throws BlueDbException {
		try {
			File file = new File(pathString);
			if (!file.exists()) {
				return new TreeMap<>();
			} else {
				byte[] bytes = Files.readAllBytes(Paths.get(pathString));
				return (TreeMap<BlueKey, BlueEntity>) serializer.asObject(bytes);
			}
		} catch (IOException e) {
			e.printStackTrace();
			throw new BlueDbException("error loading segment from disk", e);
		}
	}
	
	private void save(Object o) throws BlueDbException {
		try {
			Blutils.writeToDisk(Paths.get(pathString), o);
		} catch (IOException e) {
			e.printStackTrace();
			throw new BlueDbException("error loading segment from disk", e);
		}
	}

	@Override
	public String toString() {
		return "<Segment for path " + pathString + ">";
	}
}
