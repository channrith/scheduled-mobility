package com.mobility.core.shared.storage;

import java.nio.file.Path;

import com.mobility.core.shared.crypto.PiiCrypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class StorageConfiguration {

	@Bean
	StorageService storageService(@Value("${storage.local.dir}") Path dir, PiiCrypto crypto) {
		return new EncryptingStorageService(new LocalFileStorage(dir), crypto);
	}
}
