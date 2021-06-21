 package org.bluedb.disk.serialization;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;

import org.bluedb.api.encryption.EncryptionService;
import org.bluedb.api.keys.IntegerKey;
import org.bluedb.api.keys.LongKey;
import org.bluedb.api.keys.StringKey;
import org.bluedb.api.keys.TimeFrameKey;
import org.bluedb.api.keys.TimeKey;
import org.bluedb.api.keys.UUIDKey;
import org.bluedb.disk.Blutils;
import org.bluedb.disk.ByteUtils;
import org.bluedb.disk.collection.index.IndexCompositeKey;
import org.bluedb.disk.recovery.PendingChange;
import org.bluedb.disk.serialization.validation.ObjectValidation;
import org.bluedb.disk.serialization.validation.SerializationException;
import org.nustaq.serialization.simpleapi.DefaultCoder;

public class ThreadLocalFstSerializer extends ThreadLocal<DefaultCoder> implements BlueSerializer {
	
	private static final int MAX_ATTEMPTS = 5;

	protected final EncryptionService encryptionService;
	private Class<?>[] registeredSerializableClasses;

	public ThreadLocalFstSerializer(EncryptionService encryptionService, Class<?>...registeredSerializableClasses) {
		this.registeredSerializableClasses = registeredSerializableClasses;
		this.encryptionService = encryptionService;
	}

	@Override
	protected DefaultCoder initialValue() {
		return new DefaultCoder(true, registeredSerializableClasses);
	}

	public static Collection<? extends Class<? extends Serializable>> getClassesToAlwaysRegister() {
		return Arrays.asList(
			BlueEntity.class, 
			IntegerKey.class, 
			LongKey.class, 
			StringKey.class,
			TimeKey.class, 
			TimeFrameKey.class, 
			PendingChange.class,
			UUID.class,
			UUIDKey.class,
			IndexCompositeKey.class
		);
	}

	@Deprecated
	/** Should only be used by tests */
	public byte[] serializeObjectToByteArrayWithoutChecks(Object o) {
		return get().toByteArray(o);
	}

	@Override
	public byte[] serializeObjectToByteArray(Object o) throws SerializationException {
		validateObjectBeforeSerializing(o);
		return serializeValidObject(o);
	}

	protected byte[] serializeValidObject(Object o) throws SerializationException {
		Throwable failureCause = null;
		int retryCount = 0;
		while(retryCount < MAX_ATTEMPTS) {
			try {
				byte[] serializedBytes = get().toByteArray(o);
				validateBytesAfterSerialization(serializedBytes);
				return encryptionService.encryptOrReturn(serializedBytes);
			} catch(Throwable t) {
				failureCause = t;
				retryCount++;
			}
		}
		
		throw new SerializationException("Failed to serialize object since it keeps producing bytes that cannot be deserialized properly", failureCause); //Don't try to put the object details in the message since any usage of the invalid field will throw an exception. The caused by will contain some good detail
	}
	
	protected void validateObjectBeforeSerializing(Object o) throws SerializationException {
		try {
			validateObject(o);
		} catch(Throwable t) {
			throw new SerializationException("Cannot serialize an invalid object", t);
		}
	}

	protected void validateBytesAfterSerialization(byte[] serializedBytes) throws SerializationException {
		try {
			deserializeObjectFromByteArray(serializedBytes);
		} catch(Throwable t) {
			throw new SerializationException("Failed to serialize object since the resulting bytes cannot be deserialized properly", t);
		}
	}
	
	@Deprecated
	/** Should only be used by tests */
	public Object deserializeObjectFromByteArrayWithoutChecks(byte[] bytes) {
		return toObject(bytes);
	}

	@Override
	public Object deserializeObjectFromByteArray(byte[] bytes) throws SerializationException {
		Throwable failureCause = null;
		
		int retryCount = 0;
		while(retryCount < MAX_ATTEMPTS) {
			try {
				Object obj = toObject(bytes);
				validateObject(obj);
				return obj;
			} catch(Throwable t) {
				failureCause = t;
				retryCount++;
			}
		}
		
		throw new SerializationException("Failed to deserialize object from bytes: " + Blutils.toHex(bytes), failureCause);
	}

	private Object toObject(byte[] bytes) {
		bytes = encryptionService.decryptOrReturn(bytes);
		try {
			return get().toObject(bytes);
		} catch(Throwable t) {
			byte[] bytesToTry = ByteUtils.replaceClassPathBytes(bytes, "io.bluedb", "org.bluedb");
			return get().toObject(bytesToTry);
		}
	}

	private void validateObject(Object obj) throws SerializationException {
		try {
			ObjectValidation.validateFieldValueTypesForObject(obj);
		} catch(Throwable t) {
			throw new SerializationException("Invalid Object Identified", t); //Don't try to put the object details in the message since any usage of the invalid field will throw an exception. The caused by will contain some good detail
		}
	}
	
	@SuppressWarnings("unchecked")
	@Deprecated
	/** Should only be used by tests */
	public <T extends Serializable> T cloneWithoutChecks(T object) {
		return (T) deserializeObjectFromByteArrayWithoutChecks(serializeObjectToByteArrayWithoutChecks(object));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends Serializable> T clone(T object) throws SerializationException {
		return (T) deserializeObjectFromByteArray(serializeObjectToByteArray(object));
	}
}
