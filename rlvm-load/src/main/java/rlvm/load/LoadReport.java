package rlvm.load;

import java.util.Locale;

/**
 * Counters of one load, for the statistics dump and the loader's tests. Plain mutable fields.
 *
 * <p>The record counters count RECORDS OF THE FILE as the reader delivered them (one per line; an
 * {@code inductive} line is one declaration record with several constants), whatever became of them.
 */
public final class LoadReport {
    public long inputBytes;
    /** Lines including the header. */
    public long lines;
    public long nameRecords;
    /** {@code Name.str} / {@code Name.num} records. */
    public long nameStrRecords;
    public long nameNumRecords;
    public long levelRecords;
    public long levelSuccRecords;
    public long levelMaxRecords;
    public long levelIMaxRecords;
    public long levelParamRecords;
    /** Expression records, {@code mdata} records included. */
    public long exprRecords;
    public long exprBVar;
    public long exprSort;
    public long exprConst;
    public long exprApp;
    public long exprLam;
    public long exprForall;
    public long exprLet;
    public long exprProj;
    public long exprNatLit;
    public long exprStrLit;
    /** {@code mdata} records: dropped, the wrapped expression stands for them. */
    public long exprMData;
    /** Nat literals of 2^64 or more (stored in the literal pool). */
    public long bigNatLits;
    /** Declaration lines, dropped ones included. */
    public long declRecords;
    public long axiomRecords;
    public long defRecords;
    public long thmRecords;
    public long opaqueRecords;
    public long quotRecords;
    /** {@code inductive} lines. */
    public long inductiveRecords;
    /** Type / constructor / recursor records inside {@code inductive} lines, dropped ones included. */
    public long inductTypes;
    public long inductCtors;
    public long inductRecs;
    public long recRules;
    /** Constants written to the declaration table (dropped records excluded). */
    public long constants;
    /** Units written to the declaration table. */
    public long units;
    /** Expression records written to the shared export region (promoted copies not included). */
    public long sharedNodes;
    /** Expression records written to proof blocks. */
    public long blockNodes;
    public long blockBytes;
    /** Proof blocks written (ownerless ones included). */
    public long blocks;
    /** Theorem and opaque values that are proof blocks. */
    public long blockValues;
    /** Block records copied into the shared region because a later declaration referenced them. */
    public long promotedNodes;
    /** Promotion requests (roots of the copies above). */
    public long promotions;
    /**
     * Packed runs taken from the reader's scanner threads ({@link Loader#exprRun}), the expression
     * records in them, and the runs among them that had to be expanded into segment records (the
     * others were linked into a proof block).
     */
    public long packedRuns;
    public long packedRunRecords;
    public long expandedRuns;
    /** Constants dropped because their record was unsafe or partial. */
    public long droppedUnsafe;
    /** Expression records after the last admitted declaration (never written anywhere). */
    public long trailingExprRecords;
    /**
     * CONST/PROJ shared nodes that could not be bound when the loader wrote them: a forward reference,
     * an unknown or dropped name, or the wrong number of universe levels. 0 for exporter output.
     */
    public long unresolvedRefs;
    /**
     * CONST/PROJ proof-block records whose name had no constant when the record was written. In a
     * theorem's proof that is a forward, unknown or dropped name; in an ownerless block it only means
     * that the file wrote the record before the declaration of the name (promotion binds it again).
     */
    public long unresolvedBlockRefs;
    /** Largest number of expression records between two declaration lines. */
    public long largestSegment;
    /** True if any id space was not dense and in order (the sparse path was used). */
    public boolean sparseIds;
    /**
     * True if the reader needed an id translation table for some id space: the file's ids were not dense
     * and in order, or the ids this loader returned were not consecutive (an {@code mdata} record; a
     * name the store already knew, e.g. one of the kernel's special names; a level written twice).
     */
    public boolean readerIdTables;
    /**
     * True if any line needed the reader's general JSON path, i.e. {@link #generalPathLines} != 0. Of
     * little use: exporter output always has such lines (see there).
     */
    public boolean nonCanonicalLines;
    /**
     * Lines parsed by the reader's general JSON path. The reader's byte templates cover app, lam,
     * forallE, letE, proj, const, ASCII names, thm and safe def; every other record (levels, sort, bvar,
     * literals, names with non-ASCII or escaped characters, the other declarations) takes the general
     * path also when it is byte-canonical exporter output.
     */
    public long generalPathLines;
    public boolean metaWellFormed;
    public String exporterName;
    public String exporterVersion;
    public String formatVersion;
    public String leanVersion;
    public String leanGithash;
    public long wallNanos;
    /**
     * False if the header is well formed and announces a format version whose major is not 3. The
     * loader reads such a file all the same (the official reader ignores line 1) unless
     * {@link LoadOptions#strictFormatVersion} is set.
     */
    public boolean formatVersionSupported = true;
    /** Publications made: hand-offs of a pipelined load, a partial one before a decline, the final one. */
    public long publications;
    /**
     * Units published ({@code Store.loadedUnits()}); after a failed load this is how many units the
     * driver may still check.
     */
    public long publishedUnits;
    /** Scanner threads the reader used (1 = everything on the loader thread). */
    public int scanThreads = 1;
    /** True once {@link Loader#finish()} has completed. */
    public boolean complete;

    /** A multi-line rendering for the statistics dump. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append(String.format(Locale.ROOT, "load: %,d bytes, %,d lines, %.3f s%s%n", inputBytes, lines,
                wallNanos / 1e9, complete ? "" : " (INCOMPLETE)"));
        sb.append(String.format(Locale.ROOT, "  exporter %s %s, format %s, Lean %s%n", exporterName, exporterVersion,
                formatVersion, leanVersion));
        sb.append(String.format(Locale.ROOT, "  names %,d (str %,d, num %,d)  levels %,d (succ %,d, max %,d, imax %,d, param %,d)%n",
                nameRecords, nameStrRecords, nameNumRecords, levelRecords, levelSuccRecords, levelMaxRecords,
                levelIMaxRecords, levelParamRecords));
        sb.append(String.format(Locale.ROOT,
                "  exprs %,d: app %,d, lam %,d, forallE %,d, const %,d, letE %,d, proj %,d, strVal %,d, natVal %,d (big %,d), sort %,d, bvar %,d, mdata %,d%n",
                exprRecords, exprApp, exprLam, exprForall, exprConst, exprLet, exprProj, exprStrLit, exprNatLit,
                bigNatLits, exprSort, exprBVar, exprMData));
        sb.append(String.format(Locale.ROOT,
                "  declarations %,d: thm %,d, def %,d, opaque %,d, axiom %,d, quot %,d, inductive %,d (types %,d, ctors %,d, recs %,d, rules %,d)%n",
                declRecords, thmRecords, defRecords, opaqueRecords, axiomRecords, quotRecords, inductiveRecords,
                inductTypes, inductCtors, inductRecs, recRules));
        sb.append(String.format(Locale.ROOT,
                "  constants %,d in %,d units; dropped unsafe/partial %,d; unbound references: %,d shared nodes, %,d block records%n",
                constants, units, droppedUnsafe, unresolvedRefs, unresolvedBlockRefs));
        sb.append(String.format(Locale.ROOT,
                "  shared nodes %,d, block nodes %,d in %,d blocks (%,d bytes, %.2f B/record), block values %,d%n",
                sharedNodes, blockNodes, blocks, blockBytes, blockNodes == 0 ? 0.0 : (double) blockBytes / blockNodes,
                blockValues));
        sb.append(String.format(Locale.ROOT,
                "  promotions %,d (%,d nodes), largest segment %,d, trailing exprs %,d, general-path lines %,d, sparse ids %b, reader id tables %b%n",
                promotions, promotedNodes, largestSegment, trailingExprRecords, generalPathLines, sparseIds, readerIdTables));
        sb.append(String.format(Locale.ROOT, "  publications %,d (%,d units published), scan threads %d%s%n", publications,
                publishedUnits, scanThreads, formatVersionSupported ? "" : ", format version not 3.x (ignored)"));
        if (packedRuns != 0) {
            sb.append(String.format(Locale.ROOT, "  packed runs %,d (%,d records), expanded %,d%n", packedRuns, packedRunRecords,
                    expandedRuns));
        }
        return sb.toString();
    }
}
