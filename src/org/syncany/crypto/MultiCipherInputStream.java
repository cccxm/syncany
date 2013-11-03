package org.syncany.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

import org.syncany.util.StringUtil;

public class MultiCipherInputStream extends InputStream {
	private InputStream underlyingInputStream;

	private InputStream cipherInputStream;
	private CipherSession cipherSession;
	
	private boolean headerRead;
	private Mac headerHmac;
		
	public MultiCipherInputStream(InputStream in, CipherSession cipherSession) throws IOException {
		this.underlyingInputStream = in;		

		this.cipherInputStream = null;
		this.cipherSession = cipherSession;
		
		this.headerRead = false;		
		this.headerHmac = null;		
	}

	@Override
	public int read() throws IOException {
		if (!headerRead) {
			readHeader();		
			headerRead = true;
		}
		
		return cipherInputStream.read();
	}
	
	@Override
	public void close() throws IOException {
		cipherInputStream.close();
	}	
	
	private void readHeader() throws IOException {
		try {
			readAndVerifyMagicNoHmac(underlyingInputStream);
			readAndVerifyVersionNoHmac(underlyingInputStream);
			
			headerHmac = readHmacSaltAndInitHmac(underlyingInputStream, cipherSession.getPassword());				
			cipherInputStream = readCipherSpecsAndUpdateHmac(underlyingInputStream, headerHmac, cipherSession.getPassword());
			
			readAndVerifyHmac(underlyingInputStream, headerHmac);			
    	}
    	catch (Exception e) {
    		throw new IOException(e);
    	}
	}

	private void readAndVerifyMagicNoHmac(InputStream inputStream) throws IOException {
		byte[] streamMagic = new byte[MultiCipherOutputStream.STREAM_MAGIC.length];
		inputStream.read(streamMagic);
		
		if (!Arrays.equals(MultiCipherOutputStream.STREAM_MAGIC, streamMagic)) {
			throw new IOException("Not a Syncany-encrypted file, no magic!");
		}
	}

	private void readAndVerifyVersionNoHmac(InputStream inputStream) throws IOException {
		byte streamVersion = (byte) inputStream.read();
		
		if (streamVersion != MultiCipherOutputStream.STREAM_VERSION) {
			throw new IOException("Stream version not supported: "+streamVersion);
		}		
	}
	
	private Mac readHmacSaltAndInitHmac(InputStream inputStream, String password) throws IOException, InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException, InvalidKeyException {
		byte[] hmacSalt = readNoHmac(inputStream, MultiCipherOutputStream.SALT_SIZE);
		SecretKey hmacSecretKey = CipherUtil.createSecretKey(MultiCipherOutputStream.HMAC_ALGORITHM, MultiCipherOutputStream.HMAC_KEY_SIZE, password, hmacSalt);
		
		Mac hmac = Mac.getInstance(MultiCipherOutputStream.HMAC_ALGORITHM, CipherUtil.PROVIDER);
		hmac.init(hmacSecretKey);	
		
		return hmac;
	}
	
	private InputStream readCipherSpecsAndUpdateHmac(InputStream inputStream, Mac hmac, String password) throws IOException, InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException, CipherException {
		int cipherSpecCount = readByteAndUpdateHmac(inputStream, hmac);		
		InputStream nestedCipherInputStream = inputStream;
		
		for (int i=0; i<cipherSpecCount; i++) {
			int cipherSpecId = readByteAndUpdateHmac(inputStream, hmac);				
			CipherSpec cipherSpec = CipherSpecs.getCipherSpec(cipherSpecId);
			
			if (cipherSpec == null) {
				throw new IOException("Cannot find cipher spec with ID "+cipherSpecId);
			}

			byte[] salt = readAndUpdateHmac(inputStream, MultiCipherOutputStream.SALT_SIZE, hmac);
			byte[] iv = readAndUpdateHmac(inputStream, cipherSpec.getIvSize()/8, hmac);
			
			SecretKey secretKey = CipherUtil.createSecretKey(cipherSpec, password, salt); 
			Cipher decryptCipher = CipherUtil.createDecCipher(cipherSpec, secretKey, iv);
			
			nestedCipherInputStream = new GcmCompatibleCipherInputStream(nestedCipherInputStream, decryptCipher);		
		}	 
		
		return nestedCipherInputStream;
	}

	private void readAndVerifyHmac(InputStream inputStream, Mac hmac) throws Exception {
		byte[] calculatedHeaderHmac = hmac.doFinal();
		byte[] readHeaderHmac = readNoHmac(inputStream, calculatedHeaderHmac.length);
		
		if (!Arrays.equals(calculatedHeaderHmac, readHeaderHmac)) {
			throw new Exception("Integrity exception: Calculated HMAC "+StringUtil.toHex(calculatedHeaderHmac)+" and read HMAC "+StringUtil.toHex(readHeaderHmac)+" do not match.");
		}			
	}

	private byte[] readNoHmac(InputStream inputStream, int size) throws IOException {
		byte[] bytes = new byte[size];		
		inputStream.read(bytes);	
		
		return bytes;
	}

	private byte[] readAndUpdateHmac(InputStream inputStream, int size, Mac hmac) throws IOException {
		byte[] bytes = readNoHmac(inputStream, size);		
		hmac.update(bytes);
		
		return bytes;
	}

	private int readByteAndUpdateHmac(InputStream inputStream, Mac hmac) throws IOException {
		int abyte = inputStream.read();
		hmac.update((byte) abyte);
		
		return abyte;
	}
}