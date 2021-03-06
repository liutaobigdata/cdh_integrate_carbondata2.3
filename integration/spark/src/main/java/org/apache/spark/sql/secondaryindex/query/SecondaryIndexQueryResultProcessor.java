/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.secondaryindex.query;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.carbondata.common.CarbonIterator;
import org.apache.carbondata.common.logging.LogServiceFactory;
import org.apache.carbondata.core.constants.CarbonCommonConstants;
import org.apache.carbondata.core.datastore.block.SegmentProperties;
import org.apache.carbondata.core.datastore.exception.CarbonDataWriterException;
import org.apache.carbondata.core.datastore.row.CarbonRow;
import org.apache.carbondata.core.keygenerator.directdictionary.timestamp.DateDirectDictionaryGenerator;
import org.apache.carbondata.core.metadata.datatype.DataType;
import org.apache.carbondata.core.metadata.datatype.DataTypes;
import org.apache.carbondata.core.metadata.encoder.Encoding;
import org.apache.carbondata.core.metadata.schema.table.CarbonTable;
import org.apache.carbondata.core.metadata.schema.table.column.CarbonDimension;
import org.apache.carbondata.core.metadata.schema.table.column.ColumnSchema;
import org.apache.carbondata.core.scan.executor.infos.BlockExecutionInfo;
import org.apache.carbondata.core.scan.filter.GenericQueryType;
import org.apache.carbondata.core.scan.result.RowBatch;
import org.apache.carbondata.core.scan.result.iterator.DetailQueryResultIterator;
import org.apache.carbondata.core.scan.wrappers.ByteArrayWrapper;
import org.apache.carbondata.core.util.CarbonProperties;
import org.apache.carbondata.core.util.CarbonUtil;
import org.apache.carbondata.core.util.DataTypeUtil;
import org.apache.carbondata.processing.loading.TableProcessingOperations;
import org.apache.carbondata.processing.loading.model.CarbonLoadModel;
import org.apache.carbondata.processing.sort.exception.CarbonSortKeyAndGroupByException;
import org.apache.carbondata.processing.sort.sortdata.SingleThreadFinalSortFilesMerger;
import org.apache.carbondata.processing.sort.sortdata.SortDataRows;
import org.apache.carbondata.processing.sort.sortdata.SortIntermediateFileMerger;
import org.apache.carbondata.processing.sort.sortdata.SortParameters;
import org.apache.carbondata.processing.store.CarbonDataFileAttributes;
import org.apache.carbondata.processing.store.CarbonFactDataHandlerModel;
import org.apache.carbondata.processing.store.CarbonFactHandler;
import org.apache.carbondata.processing.store.CarbonFactHandlerFactory;
import org.apache.carbondata.processing.util.CarbonDataProcessorUtil;

import org.apache.log4j.Logger;
import org.apache.spark.sql.secondaryindex.exception.SecondaryIndexException;
import org.apache.spark.sql.secondaryindex.load.RowComparator;
import org.apache.spark.sql.secondaryindex.util.SecondaryIndexUtil;

/**
 * This class will process the query result and convert the data
 * into a format compatible for data load
 */
public class SecondaryIndexQueryResultProcessor {

  /**
   * LOGGER
   */
  private static final Logger LOGGER = LogServiceFactory.getLogService(
      SecondaryIndexQueryResultProcessor.class.getName());
  /**
   * carbon load model that contains all the required information for load
   */
  private CarbonLoadModel carbonLoadModel;
  /**
   * sortDataRows instance for sorting each row read ad writing to sort temp file
   */
  private SortDataRows sortDataRows;
  /**
   * segment properties which contains required information for a segment
   */
  private SegmentProperties segmentProperties;
  /**
   * segment information of parent table
   */
  private SegmentProperties srcSegmentProperties;
  /**
   * final merger for merge sort
   */
  private SingleThreadFinalSortFilesMerger finalMerger;
  /**
   * data handler VO object
   */
  private CarbonFactHandler dataHandler;
  /**
   * column cardinality
   */
  private int[] columnCardinality;
  /**
   * Fact Table To Index Table Column Mapping order
   */
  private int[] factToIndexColumnMapping;
  /**
   * Fact Table Dict Column to Index Table Dict Column Mapping
   */
  private int[] factToIndexDictColumnMapping;
  /**
   * boolean mapping for no dictionary columns in schema
   */
  private boolean[] noDictionaryColMapping;

  private boolean[] sortColumnMapping;

  /**
   * agg type defined for measures
   */
  private DataType[] aggType;
  /**
   * segment id
   */
  private String segmentId;
  /**
   * temp store location to be sued during data load
   */
  private String[] tempStoreLocation;
  /**
   * data base name
   */
  private String databaseName;
  /**
   * no dictionary column count in schema
   */
  private int noDictionaryCount;
  /**
   * implicit column count in schema
   */
  private int implicitColumnCount;
  /**
   * total count of measures in schema
   */
  private int measureCount;
  /**
   * dimension count excluding complex dimension and no dictionary column count
   */
  private int dimensionColumnCount;
  /**
   * complex dimension count in schema
   */
  private int complexDimensionCount;
  /**
   * index table instance
   */
  private CarbonTable indexTable;
  /**
   * whether the allocated tasks has any record
   */
  private boolean isRecordFound;
  /**
   * boolean mapping for long string dimension
   */
  private boolean[] isVarcharDimMapping;

  private SortIntermediateFileMerger intermediateFileMerger;

  private SortParameters sortParameters;

  public SecondaryIndexQueryResultProcessor(CarbonLoadModel carbonLoadModel,
      int[] columnCardinality, String segmentId, CarbonTable indexTable,
      int[] factToIndexColumnMapping) {
    this.carbonLoadModel = carbonLoadModel;
    this.columnCardinality = columnCardinality;
    this.segmentId = segmentId;
    this.indexTable = indexTable;
    this.databaseName = carbonLoadModel.getDatabaseName();
    this.factToIndexColumnMapping = factToIndexColumnMapping;
    initSegmentProperties();
  }

  /**
   * This method will iterate over the query result and convert it into a format compatible
   * for data loading
   */
  public void processQueryResult(List<CarbonIterator<RowBatch>> detailQueryResultIteratorList)
      throws SecondaryIndexException {
    try {
      initTempStoreLocation();
      initSortDataRows();
      initAggType();
      processResult(detailQueryResultIteratorList);
      // After delete command, if no records are fetched from one split,
      // below steps are not required to be initialized.
      if (isRecordFound) {
        initializeFinalThreadMergerForMergeSort();
        initDataHandler();
        readAndLoadDataFromSortTempFiles();
      }
    } finally {
      // clear temp files and folders created during secondary index creation
      String databaseName = carbonLoadModel.getDatabaseName();
      String tempLocationKey = CarbonDataProcessorUtil.getTempStoreLocationKey(databaseName,
          indexTable.getTableName(), carbonLoadModel.getSegmentId(), carbonLoadModel.getTaskNo(),
          false, false);
      TableProcessingOperations.deleteLocalDataLoadFolderLocation(tempLocationKey,
          indexTable.getTableName());
    }
  }

  public void close() {
    if (null != sortDataRows) {
      sortDataRows.close();
    }
    if (null != finalMerger) {
      finalMerger.close();
    }
    if (null != dataHandler) {
      dataHandler.finish();
      dataHandler.closeHandler();
    }
  }

  /**
   * This method will iterate over the query result and perform row sorting operation
   */
  private void processResult(List<CarbonIterator<RowBatch>> detailQueryResultIteratorList)
      throws SecondaryIndexException {
    for (CarbonIterator<RowBatch> detailQueryIterator : detailQueryResultIteratorList) {
      DetailQueryResultIterator queryIterator = (DetailQueryResultIterator) detailQueryIterator;
      BlockExecutionInfo blockExecutionInfo = queryIterator.getBlockExecutionInfo();
      // get complex dimension info map from block execution info
      Map<Integer, GenericQueryType> complexDimensionInfoMap =
          blockExecutionInfo.getComplexDimensionInfoMap();
      int[] complexColumnParentBlockIndexes =
          blockExecutionInfo.getComplexColumnParentBlockIndexes();
      while (detailQueryIterator.hasNext()) {
        RowBatch batchResult = detailQueryIterator.next();
        while (batchResult.hasNext()) {
          addRowForSorting(prepareRowObjectForSorting(batchResult.next(), complexDimensionInfoMap,
              complexColumnParentBlockIndexes));
          isRecordFound = true;
        }
      }
    }
    try {
      sortDataRows.startSorting();
    } catch (CarbonSortKeyAndGroupByException e) {
      this.sortDataRows.close();
      LOGGER.error(e);
      throw new SecondaryIndexException(
          "Problem loading data while creating secondary index: " + e.getMessage());
    }
  }

  /**
   * This method will prepare the data from raw object that will take part in sorting
   */
  private Object[] prepareRowObjectForSorting(Object[] row,
      Map<Integer, GenericQueryType> complexDimensionInfoMap, int[] complexColumnParentBlockIndexes)
      throws SecondaryIndexException {
    ByteArrayWrapper wrapper = (ByteArrayWrapper) row[0];
    byte[] implicitColumnByteArray = wrapper.getImplicitColumnByteArray();

    List<CarbonDimension> dimensions = segmentProperties.getDimensions();
    Object[] preparedRow = new Object[dimensions.size() + measureCount];
    Map<Integer, Object[]> complexDataMap = new HashMap<>();

    int noDictionaryIndex = 0;
    int dictionaryIndex = 0;
    int complexIndex = 0;
    int i = 0;
    // loop excluding last dimension as last one is implicit column.
    for (; i < dimensions.size() - 1; i++) {
      CarbonDimension dims = dimensions.get(i);
      boolean isComplexColumn = false;
      // As complex column of MainTable is stored as its primitive type in SI,
      // we need to check if dimension is complex dimension or not based on isParentColumnComplex
      // property.
      if (dims.getColumnProperties() != null && Boolean
          .parseBoolean(dims.getColumnProperties().get("isParentColumnComplex"))) {
        isComplexColumn = true;
      }
      // fill all the no dictionary and dictionary data to the prepared row first, fill the complex
      // flatten data to prepared row at last
      if (dims.hasEncoding(Encoding.DICTIONARY) && !isComplexColumn) {
        // dictionary
        preparedRow[i] = wrapper.getDictionaryKeyByIndex(dictionaryIndex++);
      } else {
        if (isComplexColumn && complexColumnParentBlockIndexes.length == 0) {
          // After restructure some complex column will not be present in parent block.
          // In such case, set the SI implicit row value to null or empty byte array.
          if (DataTypeUtil.isPrimitiveColumn(dims.getDataType())) {
            // set null value for measures
            preparedRow[i] = null;
          } else {
            preparedRow[i] = new byte[0];
          }
        } else if (isComplexColumn) {
          // get the flattened data of complex column
          byte[] complexKeyByIndex = wrapper.getComplexKeyByIndex(complexIndex);
          ByteBuffer byteArrayInput = ByteBuffer.wrap(complexKeyByIndex);
          GenericQueryType genericQueryType =
              complexDimensionInfoMap.get(complexColumnParentBlockIndexes[complexIndex++]);
          int complexDataLength = byteArrayInput.getShort(2);
          // In case, if array is empty
          if (complexDataLength == 0) {
            complexDataLength = complexDataLength + 1;
          }
          // get flattened array data
          Object[] complexFlattenedData = new Object[complexDataLength];
          Object[] data = genericQueryType.getObjectArrayDataBasedOnDataType(byteArrayInput);
          for (int index = 0; index < complexDataLength; index++) {
            complexFlattenedData[index] =
                getData(data, index, dims.getColumnSchema().getDataType());
          }
          // store the dimesnion column index and the complex column flattened data to a map
          complexDataMap.put(i, complexFlattenedData);
        } else {
          // no dictionary dims
          byte[] noDictionaryKeyByIndex = wrapper.getNoDictionaryKeyByIndex(noDictionaryIndex++);
          // no dictionary primitive columns are expected to be in original data while loading,
          // so convert it to original data
          if (DataTypeUtil.isPrimitiveColumn(dims.getDataType())) {
            Object dataFromBytes = DataTypeUtil
                .getDataBasedOnDataTypeForNoDictionaryColumn(noDictionaryKeyByIndex,
                    dims.getDataType());
            if (null != dataFromBytes && dims.getDataType() == DataTypes.TIMESTAMP) {
              dataFromBytes = (long) dataFromBytes / 1000L;
            }
            preparedRow[i] = dataFromBytes;
          } else {
            preparedRow[i] = noDictionaryKeyByIndex;
          }
        }
      }
    }

    // at last add implicit column position reference(PID)
    preparedRow[i] = implicitColumnByteArray;

    // In case of complex array type, get the flattened data based on dimension index and add
    // it to the prepared row one by one and add for sorting.
    // TODO Handle for nested array and other complex types
    if (!complexDataMap.isEmpty()) {
      Object[] firstRow = preparedRow;
      for (Map.Entry<Integer, Object[]> dataEntry : complexDataMap.entrySet()) {
        Object[] complexArrayData = dataEntry.getValue();
        preparedRow[dataEntry.getKey()] = complexArrayData[0];
        firstRow = preparedRow.clone();
        if (complexArrayData.length != 1) {
          for (int index = 1; index < complexArrayData.length; index++) {
            preparedRow[dataEntry.getKey()] = complexArrayData[index];
            addRowForSorting(preparedRow.clone());
          }
        }
      }
      preparedRow = firstRow;
    }
    return preparedRow;
  }

  /**
   * This method will return complex array primitive data
   */
  private Object getData(Object[] data, int index, DataType dataType) {
    if (data == null || data.length == 0) {
      if (DataTypeUtil.isPrimitiveColumn(dataType)) {
        return null;
      }
      return new byte[0];
    } else if (data[0] == null) {
      if (DataTypeUtil.isPrimitiveColumn(dataType)) {
        return null;
      }
      return CarbonCommonConstants.MEMBER_DEFAULT_VAL_ARRAY;
    }
    if (dataType == DataTypes.TIMESTAMP && null != data[index]) {
      return (long) data[index] / 1000L;
    } else if (dataType == DataTypes.DATE) {
      return (int) data[index] + DateDirectDictionaryGenerator.cutOffDate;
    }
    return data[index];
  }

  /**
   * This method will read sort temp files, perform merge sort and add it to store for data loading
   */
  private void readAndLoadDataFromSortTempFiles() throws SecondaryIndexException {
    Throwable throwable = null;
    try {
      Object[] previousRow = null;
      // comparator for grouping the similar data, means every record
      // should be unique in index table
      RowComparator comparator = new RowComparator(noDictionaryColMapping,
          SecondaryIndexUtil.getNoDictDataTypes(indexTable));
      intermediateFileMerger.finish();
      sortDataRows = null;
      finalMerger.startFinalMerge();
      while (finalMerger.hasNext()) {
        Object[] rowRead = finalMerger.next();
        if (null == previousRow) {
          previousRow = rowRead;
        } else {
          int compareResult = comparator.compare(previousRow, rowRead);
          if (0 == compareResult) {
            // skip similar data rows
            continue;
          } else {
            previousRow = rowRead;
          }
        }
        CarbonRow row = new CarbonRow(rowRead);
        dataHandler.addDataToStore(row);
      }
      dataHandler.finish();
    } catch (CarbonDataWriterException e) {
      LOGGER.error(e);
      throw new SecondaryIndexException("Problem loading data while creating secondary index: ", e);
    } catch (CarbonSortKeyAndGroupByException e) {
      LOGGER.error(e);
      throw new SecondaryIndexException(
          "Problem in merging intermediate files while creating secondary index: ", e);
    } catch (Throwable t) {
      LOGGER.error(t);
      throw new SecondaryIndexException("Problem while creating secondary index: ", t);
    } finally {
      if (null != dataHandler) {
        try {
          dataHandler.closeHandler();
        } catch (CarbonDataWriterException e) {
          LOGGER.error(e);
          throwable = e;
        }
      }
    }
    if (null != throwable) {
      throw new SecondaryIndexException(
          "Problem closing data handler while creating secondary index: ", throwable);
    }
    dataHandler = null;
  }

  /**
   * initialise segment properties
   */
  private void initSegmentProperties() {
    List<ColumnSchema> columnSchemaList = CarbonUtil.getColumnSchemaList(
        indexTable.getVisibleDimensions(), indexTable.getVisibleMeasures());
    segmentProperties = new SegmentProperties(columnSchemaList);
    srcSegmentProperties = new SegmentProperties(getParentColumnOrder(columnSchemaList));
  }

  /**
   * Convert index table column order into parent table column order
   */
  private List<ColumnSchema> getParentColumnOrder(List<ColumnSchema> columnSchemaList) {
    List<ColumnSchema> parentColumnList = new ArrayList<>(columnSchemaList.size());
    for (int i = 0; i < columnSchemaList.size(); i++) {
      // Extra cols are dummy_measure & positionId implicit column
      if (i >= columnCardinality.length) {
        parentColumnList.add(columnSchemaList.get(i));
      } else {
        parentColumnList.add(columnSchemaList.get(factToIndexColumnMapping[i]));
      }
    }
    return parentColumnList;
  }

  /**
   * add row to a temp array which will we written to a sort temp file after sorting
   */
  private void addRowForSorting(Object[] row) throws SecondaryIndexException {
    try {
      // prepare row array using RemoveDictionaryUtil class
      sortDataRows.addRow(row);
    } catch (CarbonSortKeyAndGroupByException e) {
      LOGGER.error(e);
      this.sortDataRows.close();
      throw new SecondaryIndexException(
          "Row addition for sorting failed while creating secondary index: " + e.getMessage());
    }
  }

  /**
   * create an instance of sort data rows
   */
  private void initSortDataRows() throws SecondaryIndexException {
    measureCount = indexTable.getVisibleMeasures().size();
    implicitColumnCount = indexTable.getImplicitDimensions().size();
    List<CarbonDimension> dimensions = indexTable.getVisibleDimensions();
    noDictionaryColMapping = new boolean[dimensions.size()];
    sortColumnMapping = new boolean[dimensions.size()];
    isVarcharDimMapping = new boolean[dimensions.size()];
    int i = 0;
    for (CarbonDimension dimension : dimensions) {
      if (dimension.isSortColumn()) {
        sortColumnMapping[i] = true;
      }
      if (CarbonUtil.hasEncoding(dimension.getEncoder(), Encoding.DICTIONARY)) {
        i++;
        continue;
      }
      noDictionaryColMapping[i] = true;
      if (dimension.getColumnSchema().getDataType() == DataTypes.VARCHAR) {
        isVarcharDimMapping[i] = true;
      }
      i++;
      noDictionaryCount++;
    }
    dimensionColumnCount = dimensions.size();
    sortParameters = createSortParameters();
    CarbonDataProcessorUtil.deleteSortLocationIfExists(sortParameters.getTempFileLocation());
    CarbonDataProcessorUtil.createLocations(sortParameters.getTempFileLocation());
    intermediateFileMerger = new SortIntermediateFileMerger(sortParameters);
    this.sortDataRows = new SortDataRows(sortParameters, intermediateFileMerger);
    this.sortDataRows.initialize();
  }

  /**
   * This method will create the sort parameters VO object
   */
  private SortParameters createSortParameters() {
    int numberOfCompactingCores = CarbonProperties.getInstance().getNumberOfCompactingCores();
    return SortParameters.createSortParameters(indexTable, databaseName, indexTable.getTableName(),
        dimensionColumnCount, complexDimensionCount, measureCount, noDictionaryCount, segmentId,
        carbonLoadModel.getTaskNo(), noDictionaryColMapping, sortColumnMapping, isVarcharDimMapping,
        false, numberOfCompactingCores / 2);
  }

  /**
   * create an instance of finalThread merger which will perform merge sort on all the
   * sort temp files
   */
  private void initializeFinalThreadMergerForMergeSort() {
    String[] sortTempFileLocation = CarbonDataProcessorUtil.arrayAppend(tempStoreLocation,
        CarbonCommonConstants.FILE_SEPARATOR, CarbonCommonConstants.SORT_TEMP_FILE_LOCATION);
    sortParameters.setNoDictionarySortColumn(
        CarbonDataProcessorUtil.getNoDictSortColMapping(indexTable));
    finalMerger = new SingleThreadFinalSortFilesMerger(sortTempFileLocation,
        indexTable.getTableName(), sortParameters);
  }

  /**
   * initialise carbon data writer instance
   */
  private void initDataHandler() throws SecondaryIndexException {
    String carbonStoreLocation = CarbonDataProcessorUtil.createCarbonStoreLocation(this.indexTable,
        segmentId);
    CarbonFactDataHandlerModel carbonFactDataHandlerModel =
        CarbonFactDataHandlerModel.getCarbonFactDataHandlerModel(carbonLoadModel, indexTable,
            segmentProperties, indexTable.getTableName(), tempStoreLocation, carbonStoreLocation);
    carbonFactDataHandlerModel.setSchemaUpdatedTimeStamp(indexTable.getTableLastUpdatedTime());
    CarbonDataFileAttributes carbonDataFileAttributes = new CarbonDataFileAttributes(
        Integer.parseInt(carbonLoadModel.getTaskNo()), carbonLoadModel.getFactTimeStamp());
    carbonFactDataHandlerModel.setCarbonDataFileAttributes(carbonDataFileAttributes);
    dataHandler = CarbonFactHandlerFactory.createCarbonFactHandler(carbonFactDataHandlerModel);
    try {
      dataHandler.initialise();
    } catch (CarbonDataWriterException e) {
      this.sortDataRows.close();
      LOGGER.error(e);
      throw new SecondaryIndexException(
          "Problem initialising data handler while creating secondary index: " + e.getMessage());
    }
  }

  /**
   * initialise temporary store location
   */
  private void initTempStoreLocation() {
    tempStoreLocation = CarbonDataProcessorUtil.getLocalDataFolderLocation(indexTable,
        carbonLoadModel.getTaskNo(), segmentId, false, false);
  }

  /**
   * initialise aggregation type for measures for their storage format
   */
  private void initAggType() {
    aggType = CarbonDataProcessorUtil.initDataType(indexTable, indexTable.getTableName(),
        measureCount);
  }
}
