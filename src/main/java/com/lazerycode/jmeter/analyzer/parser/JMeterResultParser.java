package com.lazerycode.jmeter.analyzer.parser;

import static com.lazerycode.jmeter.analyzer.config.Environment.ENVIRONMENT;
import static com.lazerycode.jmeter.analyzer.parser.StatusCodes.HTTPCODE_CONNECTIONERROR;
import static com.lazerycode.jmeter.analyzer.parser.StatusCodes.HTTPCODE_ERROR;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.lazerycode.jmeter.analyzer.statistics.Samples;

/**
 * Parses a JMeter xml result and provides {@link AggregatedResponses aggregated
 * results}
 * 
 * @author caiqiuliang
 */
public class JMeterResultParser {

	/**
	 * number of parsed items after which a log message is written
	 */
	private static final int LOGMESSAGE_ITEMS = 10000;

	/**
	 * Parses a JMeter Result XML file and provides a
	 * {@link AggregatedResponses} for every {@link Parser#getKey key}
	 * 
	 * @param reader
	 *            the JMeter xml file
	 * 
	 * @return The AggregatedResponses for every thread group
	 * 
	 * @throws IOException
	 *             If reading fails
	 * @throws SAXException
	 *             If parsing fails
	 */
	public Map<String, AggregatedResponses> aggregate(Reader reader)
			throws IOException, SAXException {

		SAXParser parser;
		try {

			parser = SAXParserFactory.newInstance().newSAXParser();
		} catch (ParserConfigurationException e) {

			throw new IllegalStateException("Parser problems", e);
		}

		Parser p = new Parser();
		parser.parse(new InputSource(reader), p);

		return p.getResults();
	}

	// ==================

	/**
	 * Parser does the heavy lifting.
	 */
	private static class Parser extends DefaultHandler {

		/**
		 * Example from JMeter results file: <httpSample t="1" lt="1"
		 * ts="1305278457847" s="false" lb="/sample/url/path.html" rc="404"
		 * rm="Not Found" tn="homepage 4-1" dt="" by="0"/>
		 */
		private static final String HTTPSAMPLE_ELEMENT = "httpSample";

		private Set<String> resultformat = new HashSet<String>();

		/**
		 * Example from JMeter results file
		 */

		private final Logger log = LoggerFactory.getLogger(getClass());
		private final AntPathMatcher matcher = new AntPathMatcher();

		private final int maxSamples;
		private final Map<String, String> pathPatterns;
		private final boolean sizeByUris;
		private final boolean durationByUris;

		private long parsedCount = 0;

		private Map<String, AggregatedResponses> results = new LinkedHashMap<String, AggregatedResponses>();

		/**
		 * Constructor. Fields configured from Environment
		 */
		public Parser() {
			this(ENVIRONMENT.getMaxSamples(), ENVIRONMENT.getRequestGroups(),
					ENVIRONMENT.isGenerateCSVs(), ENVIRONMENT.isGenerateCSVs());
			resultformat.add(HTTPSAMPLE_ELEMENT);
			resultformat.add("sample");
		}

		/**
		 * Constructor.
		 * 
		 * @param maxSamples
		 *            The maximum number of samples that be stored internally
		 *            for every metric
		 * @param pathPatterns
		 *            A number of ANT patterns. If set then the resulting
		 *            {@link AggregatedResponses} will be grouped by uris
		 *            matching these patterns. If not set then the threadgroup
		 *            is used
		 * @param sizeByUris
		 *            true, if the response size shall be counted for each uri
		 *            separately
		 * @param durationByUris
		 *            true, if the response duration shall be counted for each
		 *            uri separately
		 */
		public Parser(int maxSamples, Map<String, String> pathPatterns,
				boolean sizeByUris, boolean durationByUris) {
			this.maxSamples = maxSamples;
			this.pathPatterns = pathPatterns;
			this.sizeByUris = sizeByUris;
			this.durationByUris = durationByUris;
		}

		/**
		 * @return a mapping from identifier to aggregatedResult
		 */
		public Map<String, AggregatedResponses> getResults() {
			return results;
		}

		@Override
		public void startElement(String u, String localName, String qName,
				Attributes atts) throws SAXException {

			/*
			 * if( HTTPSAMPLE_ELEMENT.equals(localName) ||
			 * HTTPSAMPLE_ELEMENT.equals(qName) )
			 */
			/**
			 * the resultformat contains all kinds of jmeter result format such
			 * as: httpSample, sample;
			 */
			if (resultformat.contains(localName)
					|| resultformat.contains(qName)) {
				String uri = atts.getValue("lb");
				String timestampString = atts.getValue("ts");
				long timestamp = Long.parseLong(timestampString);

				boolean success = Boolean.valueOf(atts.getValue("s"));

				String key = getKey(atts);

				// --- create / provide result container
				AggregatedResponses resultContainer = getResult(key);

				// --- parse bytes
				long bytes = -1;
				String byteString = atts.getValue("by");
				try {
					bytes = Long.parseLong(byteString);
				} catch (Exception e) {
					log.warn("Error parsing bytes: '{}'", byteString);
				}

				// --- parse duration
				long duration = -1;
				String durationString = atts.getValue("t");
				try {
					duration = Long.parseLong(durationString);
				} catch (Exception e) {
					log.warn("Error parsing duration '{}'", durationString);
				}

				// --- parse responseCode
				int responseCode = getResponseCode(atts);

				// --- parse thread number
				int threadNum = getThreadNum(atts);

				// ==== add data to the resultContainer
				addData(resultContainer, uri, timestamp, bytes, duration,
						responseCode, success, threadNum);

				parsedCount++;

				// write a log message every 10000 entries
				if (parsedCount % LOGMESSAGE_ITEMS == 0) {
					log.info("Parsed {} entries ...", parsedCount);
				}
			}

			super.startElement(u, localName, qName, atts);
		}

		@Override
		public void endDocument() throws SAXException {
			super.endDocument();
			// finish collection of responses/samples
			long successCount = 0;
			double average = 0.0;
			double tps = 0.0;
			for (AggregatedResponses r : results.values()) {
				r.finish();
				//=======================================================
				/**
				 * calculate the throughout per second
				 */
				if (successCount < r.getDuration().getSuccessCount()) {
					successCount = r.getDuration().getSuccessCount();
				}
				if (average < r.getDuration().getAverage()) {
					average = r.getDuration().getAverage();
					average/=1000;
				}
				if (average != 0) {
					tps = successCount / average;
					BigDecimal bg = new BigDecimal(tps);
					bg = bg.setScale(2,BigDecimal.ROUND_UP);
					tps = bg.doubleValue();
					if(r.getTps()<tps){
						r.setTps(tps);
					}
				}
			}
			

		}

		// ==================================================================================================================

		/**
		 * Add data from httpSample to {@link AggregatedResponses the
		 * resultContainer}
		 * 
		 * @param resultContainer
		 *            container to add data to
		 * @param uri
		 *            uri identifying the resultContainer
		 * @param timestamp
		 *            httpSample timestamp
		 * @param bytes
		 *            httpSample bytes
		 * @param duration
		 *            httpSample duration
		 * @param responseCode
		 *            httpSample responseCode
		 * @param success
		 *            httpSample success
		 * @param threadNum
		 *            sample thread number
		 */
		private void addData(AggregatedResponses resultContainer, String uri,
				long timestamp, long bytes, long duration, int responseCode,
				boolean success, int threadNum) {

			StatusCodes statusCodes = resultContainer.getStatusCodes();
			statusCodes.increment(responseCode);
			resultContainer.setThreadNum(threadNum);
			// -- register data
			if (!success || bytes == -1 || duration == -1
					|| responseCode >= HTTPCODE_ERROR
					|| responseCode == HTTPCODE_CONNECTIONERROR) {

				// httpSample is not okay
				// 4xx (client error) or 5xx (server error)

				Samples requestResult = resultContainer.getDuration();
				requestResult.addError(timestamp);
				Samples bytesResult = resultContainer.getSize();
				bytesResult.addError(timestamp);
			} else {

				// httpSample is okay
				Samples bytesResult = resultContainer.getSize();
				bytesResult.addSample(timestamp, bytes);
				Samples requestResult = resultContainer.getDuration();
				requestResult.addSample(timestamp, duration);

				Map<String, Samples> sizeByUriMapping = resultContainer
						.getSizeByUri();
				Map<String, Samples> durationByUriMapping = resultContainer
						.getDurationByUri();

				add(sizeByUriMapping, uri, timestamp, bytes);
				add(durationByUriMapping, uri, timestamp, duration);
			}

			// set start and end time
			if (resultContainer.getStart() == 0) {
				resultContainer.setStart(timestamp);
			}
			resultContainer.setEnd(timestamp);

		}

		/**
		 * Create / provide {@link AggregatedResponses result container}
		 * 
		 * @param key
		 *            identifier
		 * 
		 * @return the aggregated response matching the key
		 */
		private AggregatedResponses getResult(String key) {

			AggregatedResponses resultContainer = results.get(key);
			if (resultContainer == null) {

				// initialize new AggregatedResponses
				resultContainer = new AggregatedResponses();
				resultContainer.setDuration(new Samples(maxSamples, true));
				resultContainer.setSize(new Samples(maxSamples, false));
				resultContainer.setStatusCodes(new StatusCodes());
				if (sizeByUris) {
					resultContainer
							.setSizeByUri(new HashMap<String, Samples>());
				}
				if (durationByUris) {
					resultContainer
							.setDurationByUri(new HashMap<String, Samples>());
				}

				results.put(key, resultContainer);
			}

			return resultContainer;
		}

		/**
		 * Get the reponse code from the {@link Attributes} Response code in
		 * <httpSample> element may not be an Integer, this is a safeguard
		 * against that.
		 * 
		 * @param atts
		 *            attributes to extract the response code from
		 * @return a valid response code
		 */
		private int getResponseCode(Attributes atts) {

			int responseCode;
			String responseCodeString = atts.getValue("rc");
			try {

				responseCode = Integer.valueOf(responseCodeString);
			} catch (Exception e) {
				log.warn("Error parsing response code '{}'", responseCodeString);
				responseCode = HTTPCODE_CONNECTIONERROR;
			}

			return responseCode;
		}

		/**
		 * Get the thread Number from the {@link Attributes} Thread number in
		 * <sample/httpSample> element may not be an Integer, this is a
		 * safeguard against that}
		 * 
		 * @param atts
		 * @return a valid thread number
		 */
		private int getThreadNum(Attributes atts) {
			int threadNum;
			String threadNumstr = atts.getValue("tn");
			int index = threadNumstr.indexOf("-");
			String subTn = threadNumstr.substring(index + 1,
					threadNumstr.length());
			try {
				threadNum = Integer.valueOf(subTn);
				// System.out.println("threadNum: "+threadNum);
			} catch (Exception e) {
				log.warn("Error parsing thread number '{}'", threadNumstr);
				threadNum = 0;
			}
			return threadNum;
		}

		/**
		 * Returns a key for the attributes. If a path pattern is configured,
		 * either a matching path or "default" will be returned. If no path
		 * pattern is configured, the name of the threadgroup will be used.
		 * 
		 * @param attributes
		 *            attributes to extract the key from
		 * @return the key
		 */
		private String getKey(Attributes attributes) {

			String key = null;
			if (pathPatterns != null) {

				// try to find a pattern key
				String uri = attributes.getValue("lb");
				for (Map.Entry<String, String> entry : pathPatterns.entrySet()) {
					System.out.println("pathPatterns: " + entry.getValue());
					if (matcher.match(entry.getValue(), uri)) {
						// found a pattern
						key = entry.getKey();
						break;
					}
				}

				if (key == null) {
					// no pattern found. use default
					key = "default";
				}
			} else {

				// use threadgroup name as a key
				key = attributes.getValue("tn");

				// key is now "threadgroupname int-int"
				int threadGroupSeparator = key.indexOf(' ');
				if (threadGroupSeparator > -1) {
					// cut off trailing threadno
					key = key.substring(0, threadGroupSeparator);
				}
			}

			return key;
		}

		/**
		 * Add #timestamp and matching #value to (new) Samples object matching
		 * the given #uri to given Map #uriSamples
		 * 
		 * @param uriSamples
		 *            map to add the Samples to
		 * @param uri
		 *            the uri identifying the Samples object
		 * @param timestamp
		 *            the timestamp
		 * @param value
		 *            the value
		 */
		private void add(Map<String, Samples> uriSamples, String uri,
				long timestamp, long value) {

			if (uriSamples != null) {

				Samples s = uriSamples.get(uri);

				if (s == null) {
					// no Sample was previously stored for the uri.
					s = new Samples(0, false); // 0 = don't collect samples.
												// This is important, otherwise
												// a OOM may occur if the result
												// set is big

					uriSamples.put(uri, s);
				}

				s.addSample(timestamp, value);
			}
		}

	}

}
