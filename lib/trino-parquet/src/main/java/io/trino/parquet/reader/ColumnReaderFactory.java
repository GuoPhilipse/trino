/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.parquet.reader;

import io.trino.parquet.PrimitiveField;
import io.trino.parquet.reader.decoders.ValueDecoders;
import io.trino.parquet.reader.flat.FlatColumnReader;
import io.trino.spi.TrinoException;
import io.trino.spi.type.AbstractIntType;
import io.trino.spi.type.AbstractLongType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Type;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.DateLogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.DecimalLogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.IntLogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.LogicalTypeAnnotationVisitor;
import org.apache.parquet.schema.LogicalTypeAnnotation.TimeLogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.TimestampLogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.UUIDLogicalTypeAnnotation;
import org.joda.time.DateTimeZone;

import java.util.Optional;

import static io.trino.parquet.ParquetTypeUtils.createDecimalType;
import static io.trino.parquet.reader.flat.BooleanColumnAdapter.BOOLEAN_ADAPTER;
import static io.trino.parquet.reader.flat.ByteColumnAdapter.BYTE_ADAPTER;
import static io.trino.parquet.reader.flat.IntColumnAdapter.INT_ADAPTER;
import static io.trino.parquet.reader.flat.LongColumnAdapter.LONG_ADAPTER;
import static io.trino.parquet.reader.flat.ShortColumnAdapter.SHORT_ADAPTER;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TinyintType.TINYINT;
import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.lang.String.format;
import static org.apache.parquet.schema.LogicalTypeAnnotation.TimeUnit.MICROS;
import static org.apache.parquet.schema.LogicalTypeAnnotation.TimeUnit.MILLIS;
import static org.apache.parquet.schema.LogicalTypeAnnotation.TimeUnit.NANOS;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.FLOAT;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT32;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT64;

public final class ColumnReaderFactory
{
    private ColumnReaderFactory() {}

    public static ColumnReader create(PrimitiveField field, DateTimeZone timeZone, boolean useBatchedColumnReaders)
    {
        Type type = field.getType();
        PrimitiveTypeName primitiveType = field.getDescriptor().getPrimitiveType().getPrimitiveTypeName();
        LogicalTypeAnnotation annotation = field.getDescriptor().getPrimitiveType().getLogicalTypeAnnotation();
        if (useBatchedColumnReaders && field.getDescriptor().getPath().length == 1) {
            if (BOOLEAN.equals(type) && primitiveType == PrimitiveTypeName.BOOLEAN) {
                return new FlatColumnReader<>(field, ValueDecoders::getBooleanDecoder, BOOLEAN_ADAPTER);
            }
            if (TINYINT.equals(type) && primitiveType == INT32) {
                if (isIntegerAnnotation(annotation)) {
                    return new FlatColumnReader<>(field, ValueDecoders::getByteDecoder, BYTE_ADAPTER);
                }
                throw unsupportedException(type, field);
            }
            if (SMALLINT.equals(type) && primitiveType == INT32) {
                if (isIntegerAnnotation(annotation)) {
                    return new FlatColumnReader<>(field, ValueDecoders::getShortDecoder, SHORT_ADAPTER);
                }
                throw unsupportedException(type, field);
            }
            if (DATE.equals(type) && primitiveType == INT32) {
                if (annotation == null || annotation instanceof DateLogicalTypeAnnotation) {
                    return new FlatColumnReader<>(field, ValueDecoders::getIntDecoder, INT_ADAPTER);
                }
                throw unsupportedException(type, field);
            }
            if (type instanceof AbstractIntType && primitiveType == INT32) {
                if (isIntegerAnnotation(annotation)) {
                    return new FlatColumnReader<>(field, ValueDecoders::getIntDecoder, INT_ADAPTER);
                }
                throw unsupportedException(type, field);
            }
            if (type instanceof AbstractLongType && primitiveType == INT64) {
                if (BIGINT.equals(type) && annotation instanceof TimestampLogicalTypeAnnotation) {
                    return new FlatColumnReader<>(field, ValueDecoders::getLongDecoder, LONG_ADAPTER);
                }
                if (isIntegerAnnotation(annotation)) {
                    return new FlatColumnReader<>(field, ValueDecoders::getLongDecoder, LONG_ADAPTER);
                }
            }
            if (REAL.equals(type) && primitiveType == FLOAT) {
                return new FlatColumnReader<>(field, ValueDecoders::getRealDecoder, INT_ADAPTER);
            }
            if (DOUBLE.equals(type) && primitiveType == PrimitiveTypeName.DOUBLE) {
                return new FlatColumnReader<>(field, ValueDecoders::getDoubleDecoder, LONG_ADAPTER);
            }
            if (type instanceof DecimalType decimalType && decimalType.isShort() && (primitiveType == INT32 || primitiveType == INT64)) {
                if (annotation instanceof DecimalLogicalTypeAnnotation decimalAnnotation && !isDecimalRescaled(decimalAnnotation, decimalType)) {
                    return new FlatColumnReader<>(field, ValueDecoders::getShortDecimalDecoder, LONG_ADAPTER);
                }
            }
        }

        return switch (primitiveType) {
            case BOOLEAN -> new BooleanColumnReader(field);
            case INT32 -> createDecimalColumnReader(field).orElse(new IntColumnReader(field));
            case INT64 -> {
                if (annotation instanceof TimeLogicalTypeAnnotation timeAnnotation) {
                    if (timeAnnotation.getUnit() == MICROS) {
                        yield new TimeMicrosColumnReader(field);
                    }
                    throw unsupportedException(type, field);
                }
                if (annotation instanceof TimestampLogicalTypeAnnotation timestampAnnotation) {
                    if (timestampAnnotation.getUnit() == MILLIS) {
                        yield new Int64TimestampMillisColumnReader(field);
                    }
                    if (timestampAnnotation.getUnit() == MICROS) {
                        yield new TimestampMicrosColumnReader(field);
                    }
                    if (timestampAnnotation.getUnit() == NANOS) {
                        yield new Int64TimestampNanosColumnReader(field);
                    }
                    throw unsupportedException(type, field);
                }
                yield createDecimalColumnReader(field).orElse(new LongColumnReader(field));
            }
            case INT96 -> new TimestampColumnReader(field, timeZone);
            case FLOAT -> new FloatColumnReader(field);
            case DOUBLE -> new DoubleColumnReader(field);
            case BINARY -> createDecimalColumnReader(field).orElse(new BinaryColumnReader(field));
            case FIXED_LEN_BYTE_ARRAY -> {
                Optional<PrimitiveColumnReader> decimalColumnReader = createDecimalColumnReader(field);
                if (decimalColumnReader.isPresent()) {
                    yield decimalColumnReader.get();
                }
                if (isLogicalUuid(annotation)) {
                    yield new UuidColumnReader(field);
                }
                if (annotation == null) {
                    // Iceberg 0.11.1 writes UUID as FIXED_LEN_BYTE_ARRAY without logical type annotation (see https://github.com/apache/iceberg/pull/2913)
                    // To support such files, we bet on the type to be UUID, which gets verified later, when reading the column data.
                    yield new UuidColumnReader(field);
                }
                throw unsupportedException(type, field);
            }
        };
    }

    private static boolean isLogicalUuid(LogicalTypeAnnotation annotation)
    {
        return Optional.ofNullable(annotation)
                .flatMap(logicalTypeAnnotation -> logicalTypeAnnotation.accept(new LogicalTypeAnnotationVisitor<Boolean>()
                {
                    @Override
                    public Optional<Boolean> visit(UUIDLogicalTypeAnnotation uuidLogicalType)
                    {
                        return Optional.of(TRUE);
                    }
                }))
                .orElse(FALSE);
    }

    private static Optional<PrimitiveColumnReader> createDecimalColumnReader(PrimitiveField field)
    {
        return createDecimalType(field)
                .map(decimalType -> DecimalColumnReaderFactory.createReader(field, decimalType));
    }

    private static boolean isDecimalRescaled(DecimalLogicalTypeAnnotation decimalAnnotation, DecimalType trinoType)
    {
        return decimalAnnotation.getPrecision() != trinoType.getPrecision()
                || decimalAnnotation.getScale() != trinoType.getScale();
    }

    private static boolean isIntegerAnnotation(LogicalTypeAnnotation typeAnnotation)
    {
        return typeAnnotation == null || typeAnnotation instanceof IntLogicalTypeAnnotation || isZeroScaleDecimalAnnotation(typeAnnotation);
    }

    private static boolean isZeroScaleDecimalAnnotation(LogicalTypeAnnotation typeAnnotation)
    {
        return typeAnnotation instanceof DecimalLogicalTypeAnnotation
                && ((DecimalLogicalTypeAnnotation) typeAnnotation).getScale() == 0;
    }

    private static TrinoException unsupportedException(Type type, PrimitiveField field)
    {
        return new TrinoException(NOT_SUPPORTED, format("Unsupported Trino column type (%s) for Parquet column (%s)", type, field.getDescriptor()));
    }
}
