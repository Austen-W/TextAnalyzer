package com.seaboat.text.analyzer.training;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;

import smile.classification.SVM;
import smile.math.kernel.GaussianKernel;

import com.seaboat.text.analyzer.IDF;
import com.seaboat.text.analyzer.hotword.LuceneMemoryIDF;
import com.seaboat.text.analyzer.util.DataReader;
import com.seaboat.text.analyzer.util.MemoryIndexUtil;
import com.seaboat.text.analyzer.util.ObjectUtil;
import com.seaboat.text.analyzer.util.StringUtil;

/**
 * 
 * @author seaboat
 * @date 2017-05-24
 * @version 1.0
 * <pre><b>email: </b>849586227@qq.com</pre>
 * <pre><b>blog: </b>http://blog.csdn.net/wangyangzhizhou</pre>
 * <p>SVM Trainer.</p>
 */
public class SVMTrainer {

	protected static Logger logger = Logger.getLogger(SVMTrainer.class);

	private IDF idf = new LuceneMemoryIDF();

	public static String TRAIN_FILE = "data/train.txt";

	public static String TEST_FILE = "data/test.txt";

	public static String MODEL_FILE = "model/svm-model";

	public static String VECTOR_FILE = "model/vector";

	public static String TFIDF_FILE = "model/tfidf";

	public SVMTrainer() {

	}

	public SVMTrainer(String model_file, String vector_file, String tfidf_file) {
		MODEL_FILE = model_file;
		VECTOR_FILE = vector_file;
		TFIDF_FILE = tfidf_file;
	}

	public void train() {
		List<Integer> labels = new LinkedList<Integer>();
		int docNum = 0;
		List<String> list = DataReader.readContent(TRAIN_FILE);
		IndexWriter indexWriter = null;
		try {
			indexWriter = MemoryIndexUtil.getIndexWriter();
		} catch (IOException e) {
			logger.error("IOException when getting index writer. ", e);
		}
		int DOCID = 0;
		int delta = 10000;
		for (String line : list) {
			labels.add(Integer.parseInt(line.split("\\|")[0]));
			String text = line.split("\\|")[1];
			FieldType type = new FieldType();
			type.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
			type.setStored(true);
			type.setStoreTermVectors(true);
			type.setTokenized(true);
			Document doc = new Document();
			Field field = new Field("content", text, type);
			Field docIdField = new Field("docId", String.valueOf(DOCID + delta), type);
			doc.add(field);
			doc.add(docIdField);
			try {
				docNum++;
				indexWriter.addDocument(doc);
				indexWriter.commit();
			} catch (IOException e) {
				logger.error("IOException when adding document. ", e);
			}
			DOCID++;
		}
		// get a whole term vector
		IndexReader reader = null;
		Set<String> vector = null;
		try {
			reader = MemoryIndexUtil.getIndexReader();
			vector = new HashSet<String>();
			for (int docId = 0; docId < reader.maxDoc(); docId++) {
				Term t = new Term("docId", String.valueOf(docId + delta));
				Query query = new TermQuery(t);
				IndexSearcher searcher = new IndexSearcher(reader);
				TopDocs topDocs = searcher.search(query, 1);
				Terms terms = reader.getTermVector(topDocs.scoreDocs[0].doc, "content");
				TermsEnum termsEnum = terms.iterator();
				BytesRef thisTerm = null;
				while ((thisTerm = termsEnum.next()) != null) {
					String term = thisTerm.utf8ToString();
					if ((term.length() > 1) && (!StringUtil.isNumericAndLetter(term)) && (!StringUtil.isMobile(term))
							&& (!StringUtil.isPhone(term)) && (!StringUtil.isContainNumber(term))
							&& (!StringUtil.isDate(term)) && (!StringUtil.isFilterWord(term)))
						vector.add(term);
				}
			}
		} catch (IOException e) {
			logger.error("IOException happens ", e);
		}

		List<String> vectorList = new ArrayList<String>(vector);
		Map<String, Double> tfidf = new HashMap<String, Double>();
		// calculating the vector of samples
		double[][] samples = new double[labels.size()][vectorList.size()];
		for (int i = 0; i < docNum; i++) {
			try {
				Term t = new Term("docId", String.valueOf(i + delta));
				Query query = new TermQuery(t);
				IndexSearcher searcher = new IndexSearcher(reader);
				TopDocs topDocs;
				topDocs = searcher.search(query, 1);
				int j = 0;
				for (int m = 0; m < vectorList.size(); m++) {
					String vTerm = vectorList.get(m);
					Terms terms = reader.getTermVector(topDocs.scoreDocs[0].doc, "content");
					TermsEnum termsEnum = terms.iterator();
					BytesRef thisTerm = null;
					boolean isContain = false;
					while ((thisTerm = termsEnum.next()) != null) {
						String term = thisTerm.utf8ToString();
						if (term.equals(vTerm)) {
							float idfn = idf.getIDF(term);
							int a = (int) termsEnum.totalTermFreq();
							long b = terms.size();
							float tf = (float) a / b;
							samples[i][j] = idfn * tf;
							tfidf.put(term, samples[i][j]);
							isContain = true;
							break;
						}
					}
					// not contain means this vector value is 0
					if (!isContain)
						samples[i][j] = 0.0;
					j++;
				}
			} catch (IOException e) {
				logger.error("IOException happens ", e);
			}
		}
		Integer tmpInteger[] = new Integer[labels.size()];
		int labelInt[] = new int[labels.size()];
		labels.toArray(tmpInteger);
		for (int i = 0; i < tmpInteger.length; i++) {
			labelInt[i] = tmpInteger[i].intValue();
		}

		SVM<double[]> svm = new SVM<double[]>(new GaussianKernel(8.0), 500.0, 13, SVM.Multiclass.ONE_VS_ALL);
		svm.learn(samples, labelInt);
		svm.finish();

		saveModel(svm);
		saveVector(vectorList);
		saveTFIDF(tfidf);
	}

	private void saveTFIDF(Map<String, Double> tfidf) {
		ObjectUtil.saveObjectToFile(tfidf, TFIDF_FILE);
	}

	private void saveVector(List<String> vectorList) {
		ObjectUtil.saveObjectToFile(vectorList, VECTOR_FILE);
	}

	public void saveModel(SVM<double[]> svm) {
		ObjectUtil.saveObjectToFile(svm, MODEL_FILE);
	}

	public int predict(double[] data) {
		@SuppressWarnings("unchecked")
		SVM<double[]> svm = (SVM<double[]>) ObjectUtil.readObjectFromFile(MODEL_FILE);
		return svm.predict(data);
	}

	public double[] getWordVector(String text) {
		List<String> termList = MemoryIndexUtil.getToken(text);
		@SuppressWarnings("unchecked")
		List<String> vectorList = (List<String>) ObjectUtil.readObjectFromFile(VECTOR_FILE);
		@SuppressWarnings("unchecked")
		Map<String, Double> tfidf = (Map<String, Double>) ObjectUtil.readObjectFromFile(TFIDF_FILE);
		double[] data = new double[vectorList.size()];
		int j = 0;
		for (String vTerm : vectorList) {
			if (termList.contains(vTerm)) {
				double value = tfidf.get(vTerm);
				if (value == 0)
					data[j] = 0.0;
				else
					data[j] = value;
			} else {
				// not contain means this vector value is 0
				data[j] = 0.0;
			}
			j++;
		}
		return data;
	}

}
