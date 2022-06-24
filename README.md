
## Features
CarbonData file format is a columnar store in HDFS, it has many features that a modern columnar format has, such as splittable, compression schema, complex data type etc, and CarbonData has following unique features:
* Stores data along with index: it can significantly accelerate query performance and reduces the I/O scans and CPU resources, where there are filters in the query.  CarbonData index consists of multiple level of indices, a processing framework can leverage this index to reduce the task it needs to schedule and process, and it can also do skip scan in more finer grain unit (called blocklet) in task side scanning instead of scanning the whole file.
* Operable encoded data: through supporting efficient compression and global encoding schemes, can query on compressed/encoded data, the data can be converted just before returning the results to the users, which is "late materialized".
* Supports for various use cases with one single Data format : like interactive OLAP-style query, Sequential Access (big scan), Random Access (narrow scan).

## Reference 
https://github.com/apache/carbondata

