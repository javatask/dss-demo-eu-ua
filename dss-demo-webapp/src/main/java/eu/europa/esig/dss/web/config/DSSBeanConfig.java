package eu.europa.esig.dss.web.config;

import eu.europa.esig.dss.alert.ExceptionOnStatusAlert;
import eu.europa.esig.dss.asic.cades.signature.ASiCWithCAdESService;
import eu.europa.esig.dss.asic.xades.signature.ASiCWithXAdESService;
import eu.europa.esig.dss.cades.signature.CAdESService;
import eu.europa.esig.dss.jades.signature.JAdESService;
import eu.europa.esig.dss.model.DSSException;
import eu.europa.esig.dss.pades.signature.PAdESService;
import eu.europa.esig.dss.service.crl.JdbcCacheCRLSource;
import eu.europa.esig.dss.service.crl.OnlineCRLSource;
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader;
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader;
import eu.europa.esig.dss.service.http.commons.OCSPDataLoader;
import eu.europa.esig.dss.service.http.commons.SSLCertificateLoader;
import eu.europa.esig.dss.service.http.proxy.ProxyConfig;
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource;
import eu.europa.esig.dss.service.x509.aia.JdbcCacheAIASource;
import eu.europa.esig.dss.spi.DSSUtils;
import eu.europa.esig.dss.spi.client.http.DSSFileLoader;
import eu.europa.esig.dss.spi.client.http.IgnoreDataLoader;
import eu.europa.esig.dss.spi.client.jdbc.JdbcCacheConnector;
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource;
import eu.europa.esig.dss.spi.x509.CertificateSource;
import eu.europa.esig.dss.spi.x509.CommonCertificateSource;
import eu.europa.esig.dss.spi.x509.KeyStoreCertificateSource;
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource;
import eu.europa.esig.dss.spi.x509.aia.OnlineAIASource;
import eu.europa.esig.dss.spi.x509.tsp.TSPSource;
import eu.europa.esig.dss.token.KeyStoreSignatureTokenConnection;
import eu.europa.esig.dss.tsl.function.GrantedTrustService;
import eu.europa.esig.dss.tsl.function.OfficialJournalSchemeInformationURI;
import eu.europa.esig.dss.tsl.job.TLValidationJob;
import eu.europa.esig.dss.tsl.source.LOTLSource;
import eu.europa.esig.dss.tsl.source.TLSource;
import eu.europa.esig.dss.validation.CertificateVerifier;
import eu.europa.esig.dss.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.validation.SignaturePolicyProvider;
import eu.europa.esig.dss.ws.cert.validation.common.RemoteCertificateValidationService;
import eu.europa.esig.dss.ws.server.signing.common.RemoteSignatureTokenConnection;
import eu.europa.esig.dss.ws.server.signing.common.RemoteSignatureTokenConnectionImpl;
import eu.europa.esig.dss.ws.signature.common.RemoteDocumentSignatureServiceImpl;
import eu.europa.esig.dss.ws.signature.common.RemoteMultipleDocumentsSignatureServiceImpl;
import eu.europa.esig.dss.ws.timestamp.remote.RemoteTimestampService;
import eu.europa.esig.dss.ws.validation.common.RemoteDocumentValidationService;
import eu.europa.esig.dss.xades.signature.XAdESService;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportResource;
import org.springframework.core.io.ClassPathResource;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.security.KeyStore.PasswordProtection;
import java.sql.SQLException;

@Configuration
@ComponentScan(basePackages = { "eu.europa.esig.dss.web.job", "eu.europa.esig.dss.web.service" })
@Import({ PropertiesConfig.class, CXFConfig.class, PersistenceConfig.class, ProxyConfiguration.class, WebSecurityConfig.class,
		SchedulingConfig.class })
@ImportResource({ "${tsp-source}" })
public class DSSBeanConfig {

	private static final Logger LOG = LoggerFactory.getLogger(DSSBeanConfig.class);

	@Value("${default.validation.policy}")
	private String defaultValidationPolicy;

	@Value("${current.lotl.url}")
	private String lotlUrl;

	@Value("${lotl.country.code}")
	private String lotlCountryCode;

	@Value("${current.oj.url}")
	private String currentOjUrl;

	@Value("${oj.content.keystore.type}")
	private String ksType;

	@Value("${oj.content.keystore.filename}")
	private String ksFilename;

	@Value("${oj.content.keystore.password}")
	private String ksPassword;

	@Value("${dss.server.signing.keystore.type}")
	private String serverSigningKeystoreType;

	@Value("${dss.server.signing.keystore.filename}")
	private String serverSigningKeystoreFilename;

	@Value("${dss.server.signing.keystore.password}")
	private String serverSigningKeystorePassword;

	@Autowired
	private TSPSource tspSource;

	@Autowired
	private DataSource dataSource;

	@Value("${dataloader.connection.timeout}")
	private int connectionTimeout;
	@Value("${dataloader.connection.request.timeout}")
	private int connectionRequestTimeout;
	@Value("${dataloader.redirect.enabled}")
	private boolean redirectEnabled;

	// can be null
	@Autowired(required = false)
	private ProxyConfig proxyConfig;

	@Bean
	public CommonsDataLoader dataLoader() {
		return configureCommonsDataLoader(new CommonsDataLoader());
	}
	
	@Bean
    public CommonsDataLoader trustAllDataLoader() {
		CommonsDataLoader trustAllDataLoader = configureCommonsDataLoader(new CommonsDataLoader());
		trustAllDataLoader.setTrustStrategy(TrustAllStrategy.INSTANCE);
		return trustAllDataLoader;
    }

	@Bean
	public OCSPDataLoader ocspDataLoader() {
		return configureCommonsDataLoader(new OCSPDataLoader());
	}

	@Bean
	public FileCacheDataLoader fileCacheDataLoader() {
		FileCacheDataLoader fileCacheDataLoader = new FileCacheDataLoader();
		fileCacheDataLoader.setDataLoader(dataLoader());
		// Per default uses "java.io.tmpdir" property
		// fileCacheDataLoader.setFileCacheDirectory(new File("/tmp"));
		return fileCacheDataLoader;
	}

	@Bean
	public OnlineAIASource onlineAIASource() {
		return new DefaultAIASource(dataLoader());
	}

	@Bean
	public JdbcCacheAIASource cachedAIASource() {
		JdbcCacheAIASource jdbcCacheAIASource = new JdbcCacheAIASource();
		jdbcCacheAIASource.setJdbcCacheConnector(jdbcCacheConnector());
		jdbcCacheAIASource.setProxySource(onlineAIASource());
		return jdbcCacheAIASource;
	}

	@Bean
	public OnlineCRLSource onlineCRLSource() {
		OnlineCRLSource onlineCRLSource = new OnlineCRLSource();
		onlineCRLSource.setDataLoader(dataLoader());
		return onlineCRLSource;
	}

	@Bean
	public JdbcCacheCRLSource cachedCRLSource() {
		JdbcCacheCRLSource jdbcCacheCRLSource = new JdbcCacheCRLSource();
		jdbcCacheCRLSource.setJdbcCacheConnector(jdbcCacheConnector());
		jdbcCacheCRLSource.setProxySource(onlineCRLSource());
		jdbcCacheCRLSource.setDefaultNextUpdateDelay((long) (60 * 10)); // 10 minutes
		return jdbcCacheCRLSource;
	}

	@Bean
	public OnlineOCSPSource onlineOcspSource() {
		OnlineOCSPSource onlineOCSPSource = new OnlineOCSPSource();
		onlineOCSPSource.setDataLoader(ocspDataLoader());
		return onlineOCSPSource;
	}

	@Bean
	public JdbcCacheConnector jdbcCacheConnector() {
		return new JdbcCacheConnector(dataSource);
	}

	@Bean
	public SignaturePolicyProvider signaturePolicyProvider() {
		SignaturePolicyProvider signaturePolicyProvider = new SignaturePolicyProvider();
		signaturePolicyProvider.setDataLoader(fileCacheDataLoader());
		return signaturePolicyProvider;
	}

	@Bean(name = "european-trusted-list-certificate-source")
	public TrustedListsCertificateSource trustedListSource() {
		return new TrustedListsCertificateSource();
	}

	@Bean
	public CertificateVerifier certificateVerifier() {
		CommonCertificateVerifier certificateVerifier = new CommonCertificateVerifier();
		certificateVerifier.setCrlSource(cachedCRLSource());
		certificateVerifier.setOcspSource(onlineOcspSource());
		certificateVerifier.setAIASource(cachedAIASource());
		certificateVerifier.setTrustedCertSources(trustedListSource());

		// Default configs
		certificateVerifier.setAlertOnMissingRevocationData(new ExceptionOnStatusAlert());
		certificateVerifier.setCheckRevocationForUntrustedChains(false);

		return certificateVerifier;
	}

	@Bean
	public ClassPathResource defaultPolicy() {
		return new ClassPathResource(defaultValidationPolicy);
	}

	@Bean
	public CAdESService cadesService() {
		CAdESService service = new CAdESService(certificateVerifier());
		service.setTspSource(tspSource);
		return service;
	}

	@Bean
	public XAdESService xadesService() {
		XAdESService service = new XAdESService(certificateVerifier());
		service.setTspSource(tspSource);
		return service;
	}

	@Bean
	public PAdESService padesService() {
		PAdESService service = new PAdESService(certificateVerifier());
		service.setTspSource(tspSource);
		return service;
	}

	@Bean
	public JAdESService jadesService() {
		JAdESService service = new JAdESService(certificateVerifier());
		service.setTspSource(tspSource);
		return service;
	}

	@Bean
	public ASiCWithCAdESService asicWithCadesService() {
		ASiCWithCAdESService service = new ASiCWithCAdESService(certificateVerifier());
		service.setTspSource(tspSource);
		return service;
	}

	@Bean
	public ASiCWithXAdESService asicWithXadesService() {
		ASiCWithXAdESService service = new ASiCWithXAdESService(certificateVerifier());
		service.setTspSource(tspSource);
		return service;
	}

	@Bean
	public RemoteDocumentSignatureServiceImpl remoteSignatureService() {
		RemoteDocumentSignatureServiceImpl service = new RemoteDocumentSignatureServiceImpl();
		service.setAsicWithCAdESService(asicWithCadesService());
		service.setAsicWithXAdESService(asicWithXadesService());
		service.setCadesService(cadesService());
		service.setXadesService(xadesService());
		service.setPadesService(padesService());
		service.setJadesService(jadesService());
		return service;
	}

	@Bean
	public RemoteMultipleDocumentsSignatureServiceImpl remoteMultipleDocumentsSignatureService() {
		RemoteMultipleDocumentsSignatureServiceImpl service = new RemoteMultipleDocumentsSignatureServiceImpl();
		service.setAsicWithCAdESService(asicWithCadesService());
		service.setAsicWithXAdESService(asicWithXadesService());
		service.setXadesService(xadesService());
		service.setJadesService(jadesService());
		return service;
	}

	@Bean
	public RemoteDocumentValidationService remoteValidationService() {
		RemoteDocumentValidationService service = new RemoteDocumentValidationService();
		service.setVerifier(certificateVerifier());
		return service;
	}
	
	@Bean
	public RemoteCertificateValidationService RemoteCertificateValidationService() {
		RemoteCertificateValidationService service = new RemoteCertificateValidationService();
		service.setVerifier(certificateVerifier());
		return service;
	}

	@Bean
	public KeyStoreSignatureTokenConnection remoteToken() throws IOException {
		return new KeyStoreSignatureTokenConnection(new ClassPathResource(serverSigningKeystoreFilename).getFile(), serverSigningKeystoreType,
				new PasswordProtection(serverSigningKeystorePassword.toCharArray()));
	}

	@Bean
	public RemoteSignatureTokenConnection serverToken() throws IOException {
		RemoteSignatureTokenConnectionImpl remoteSignatureTokenConnectionImpl = new RemoteSignatureTokenConnectionImpl();
		remoteSignatureTokenConnectionImpl.setToken(remoteToken());
		return remoteSignatureTokenConnectionImpl;
	}
	
	@Bean
	public RemoteTimestampService timestampService() throws IOException {
		RemoteTimestampService timestampService = new RemoteTimestampService();
		timestampService.setTSPSource(tspSource);
		return timestampService;
	}

	@Bean
	public KeyStoreCertificateSource ojContentKeyStore() {
		try {
			return new KeyStoreCertificateSource(new ClassPathResource(ksFilename).getFile(), ksType, ksPassword);
		} catch (IOException e) {
			throw new DSSException("Unable to load the file " + ksFilename, e);
		}
	}

	private TLSource ukranianTLSource() {
		TLSource tlSource = new TLSource();
		// Mandatory : The url where the TL needs to be downloaded
		tlSource.setUrl("https://czo.gov.ua/download/tl/TL-UA.xml");

		// A certificate source which contains the signing certificate(s) for the
		// current trusted list
		CertificateSource cs = new CommonCertificateSource();
		cs.addCertificate(DSSUtils.loadCertificateFromBase64EncodedString(
				"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"));
		tlSource.setCertificateSource(cs);

		return tlSource;
	}
	
	@Bean 
	public TLValidationJob job() {
		TLValidationJob job = new TLValidationJob();
		job.setTrustedListCertificateSource(trustedListSource());
		job.setTrustedListSources(ukranianTLSource());
		job.setListOfTrustedListSources(europeanLOTL());
		job.setOfflineDataLoader(offlineLoader());
		job.setOnlineDataLoader(onlineLoader());
		return job;
	}

	@Bean
	public DSSFileLoader onlineLoader() {
		FileCacheDataLoader onlineFileLoader = new FileCacheDataLoader();
		onlineFileLoader.setCacheExpirationTime(0);
		onlineFileLoader.setDataLoader(dataLoader());
		onlineFileLoader.setFileCacheDirectory(tlCacheDirectory());
		return onlineFileLoader;
	}

	@Bean(name = "european-lotl-source")
	public LOTLSource europeanLOTL() {
		LOTLSource lotlSource = new LOTLSource();
		lotlSource.setUrl(lotlUrl);
		lotlSource.setCertificateSource(ojContentKeyStore());
		lotlSource.setSigningCertificatesAnnouncementPredicate(new OfficialJournalSchemeInformationURI(currentOjUrl));
		lotlSource.setPivotSupport(true);
		return lotlSource;
	}

	@Bean
	public DSSFileLoader offlineLoader() {
		FileCacheDataLoader offlineFileLoader = new FileCacheDataLoader();
		offlineFileLoader.setCacheExpirationTime(Long.MAX_VALUE);
		offlineFileLoader.setDataLoader(new IgnoreDataLoader());
		offlineFileLoader.setFileCacheDirectory(tlCacheDirectory());
		return offlineFileLoader;
	}

	@Bean
	public File tlCacheDirectory() {
		File rootFolder = new File(System.getProperty("java.io.tmpdir"));
		File tslCache = new File(rootFolder, "dss-tsl-loader");
		if (tslCache.mkdirs()) {
			LOG.info("TL Cache folder : {}", tslCache.getAbsolutePath());
		}
		return tslCache;
	}

	/* JDBC functions */

	@PostConstruct
	public void cachedAIASourceInitialization() throws SQLException {
		JdbcCacheAIASource jdbcCacheAIASource = cachedAIASource();
		jdbcCacheAIASource.initTable();
	}

	@PostConstruct
	public void cachedCRLSourceInitialization() throws SQLException {
		JdbcCacheCRLSource jdbcCacheCRLSource = cachedCRLSource();
		jdbcCacheCRLSource.initTable();
	}

	@PreDestroy
	public void cachedAIASourceClean() throws SQLException {
		JdbcCacheAIASource jdbcCacheAIASource = cachedAIASource();
		jdbcCacheAIASource.destroyTable();
	}

	@PreDestroy
	public void cachedCRLSourceClean() throws SQLException {
		JdbcCacheCRLSource jdbcCacheCRLSource = cachedCRLSource();
		jdbcCacheCRLSource.destroyTable();
	}

	// Cached OCSPSource is not used
	
    /* QWAC Validation */

    @Bean
    public SSLCertificateLoader sslCertificateLoader() {
        SSLCertificateLoader sslCertificateLoader = new SSLCertificateLoader();
        sslCertificateLoader.setCommonsDataLoader(trustAllDataLoader());
        return sslCertificateLoader;
    }

	private <C extends CommonsDataLoader> C configureCommonsDataLoader(C dataLoader) {
		dataLoader.setTimeoutConnection(connectionTimeout);
		dataLoader.setTimeoutConnectionRequest(connectionRequestTimeout);
		dataLoader.setRedirectsEnabled(redirectEnabled);
		dataLoader.setProxyConfig(proxyConfig);
		return dataLoader;
	}

}