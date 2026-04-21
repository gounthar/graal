/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.graal.llvm.util;

import static com.oracle.svm.core.graal.llvm.util.LLVMUtils.FALSE;
import static com.oracle.svm.core.graal.llvm.util.LLVMUtils.TRUE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import jdk.graal.compiler.code.CompilationResult;
import jdk.graal.compiler.core.common.NumUtil;
import jdk.graal.compiler.debug.GraalError;

import com.oracle.objectfile.ObjectFile;
import com.oracle.objectfile.SectionName;
import com.oracle.svm.core.graal.llvm.LLVMGenerator;
import com.oracle.svm.core.graal.llvm.LLVMNativeImageCodeCache.StackMapDumper;
import com.oracle.svm.core.heap.SubstrateReferenceMap;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.shadowed.org.bytedeco.javacpp.BytePointer;
import com.oracle.svm.shadowed.org.bytedeco.javacpp.Pointer;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMMemoryBufferRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMBinaryRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMObjectFileRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMSectionIteratorRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMSymbolIteratorRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.global.LLVM;

import jdk.vm.ci.code.DebugInfo;
import jdk.vm.ci.code.ReferenceMap;
import jdk.vm.ci.code.site.Call;
import jdk.vm.ci.code.site.Infopoint;
import jdk.vm.ci.code.site.InfopointReason;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public class LLVMObjectFileReader {
    private static final String SYMBOL_PREFIX = (ObjectFile.getNativeFormat() == ObjectFile.Format.MACH_O) ? "_" : "";
    private final StackMapDumper stackMapDumper;

    static {
        LLVM.LLVMInitializeAllTargetInfos();
        LLVM.LLVMInitializeAllTargets();
        LLVM.LLVMInitializeAllTargetMCs();
    }

    public LLVMObjectFileReader(StackMapDumper stackMapDumper) {
        this.stackMapDumper = stackMapDumper;
    }

    @FunctionalInterface
    private interface SectionReader<Result> {
        Result apply(LLVMSectionIteratorRef sectionIterator, LLVMSectionIteratorRef relocationsSectionIterator);
    }

    @FunctionalInterface
    private interface SymbolReader<Symbol> {
        Symbol apply(LLVMSymbolIteratorRef symbolIterator, LLVMSectionIteratorRef sectionIterator);
    }

    private static final class LLVMSectionInfo<Section, Symbol> {
        private Section sectionInfo;
        private List<Symbol> symbolInfo = new ArrayList<>();
    }

    private static <SectionInfo, SymbolInfo> LLVMSectionInfo<SectionInfo, SymbolInfo> readSection(Path path, SectionName sectionName, SectionReader<SectionInfo> sectionReader,
                    SymbolReader<SymbolInfo> symbolReader) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new GraalError(e);
        }

        LLVMMemoryBufferRef buffer = LLVM.LLVMCreateMemoryBufferWithMemoryRangeCopy(new BytePointer(bytes), bytes.length, new BytePointer(""));
        BytePointer errorMessage = new BytePointer((Pointer) null);
        LLVMBinaryRef binary = LLVM.LLVMCreateBinary(buffer, (com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMContextRef) null, errorMessage);
        if (binary == null || binary.isNull()) {
            String errMsg = (errorMessage != null && !errorMessage.isNull()) ? errorMessage.getString() : "unknown error";
            throw new GraalError("LLVMCreateBinary failed for " + path + ": " + errMsg);
        }

        LLVMSectionIteratorRef sectionIterator;
        LLVMSectionIteratorRef relocationsSectionIterator = LLVM.LLVMObjectFileCopySectionIterator(binary);
        LLVMSectionInfo<SectionInfo, SymbolInfo> result = new LLVMSectionInfo<>();
        for (sectionIterator = LLVM.LLVMObjectFileCopySectionIterator(binary); LLVM.LLVMObjectFileIsSectionIteratorAtEnd(binary, sectionIterator) == FALSE; LLVM.LLVMMoveToNextSection(sectionIterator)) {
            BytePointer sectionNamePointer = LLVM.LLVMGetSectionName(sectionIterator);
            String currentSectionName = (sectionNamePointer != null) ? sectionNamePointer.getString() : "";
            if (currentSectionName.startsWith(sectionName.getFormatDependentName(ObjectFile.getNativeFormat()))) {
                result.sectionInfo = sectionReader.apply(sectionIterator, relocationsSectionIterator);

                if (symbolReader != null) {
                    LLVMSymbolIteratorRef symbolIterator;
                    for (symbolIterator = LLVM.LLVMObjectFileCopySymbolIterator(binary); LLVM.LLVMObjectFileIsSymbolIteratorAtEnd(binary, symbolIterator) == FALSE; LLVM.LLVMMoveToNextSymbol(symbolIterator)) {
                        if (LLVM.LLVMGetSectionContainsSymbol(sectionIterator, symbolIterator) == TRUE) {
                            result.symbolInfo.add(symbolReader.apply(symbolIterator, sectionIterator));
                        }
                    }
                    LLVM.LLVMDisposeSymbolIterator(symbolIterator);
                }
                break;
            }
            LLVM.LLVMMoveToNextSection(relocationsSectionIterator);
        }

        LLVM.LLVMDisposeSectionIterator(sectionIterator);
        LLVM.LLVMDisposeSectionIterator(relocationsSectionIterator);
        LLVM.LLVMDisposeBinary(binary);

        return result;
    }

    private static final class SymbolOffset {
        private final String symbol;
        private final int offset;

        private SymbolOffset(String symbol, int offset) {
            this.symbol = symbol;
            this.offset = offset;
        }
    }

    public LLVMTextSectionInfo parseCode(Path objectFile) {
        LLVMSectionInfo<Long, SymbolOffset> sectionInfo = readSection(objectFile, SectionName.TEXT, this::parseTextSection, this::handleTextSymbol);
        return new LLVMTextSectionInfo(sectionInfo);
    }

    private Long parseTextSection(LLVMSectionIteratorRef sectionIterator, @SuppressWarnings("unused") LLVMSectionIteratorRef relocationsSectionIterator) {
        return LLVM.LLVMGetSectionSize(sectionIterator);
    }

    private SymbolOffset handleTextSymbol(LLVMSymbolIteratorRef symbolIterator, LLVMSectionIteratorRef sectionIterator) {
        long sectionAddress = LLVM.LLVMGetSectionAddress(sectionIterator);
        int offset = NumUtil.safeToInt(LLVM.LLVMGetSymbolAddress(symbolIterator) - sectionAddress);
        String symbolName = LLVM.LLVMGetSymbolName(symbolIterator).getString();
        return new SymbolOffset(symbolName, offset);
    }

    public LLVMStackMapInfo parseStackMap(Path objectFile) {
        // Pure-Java ELF64 path: avoids libLLVM-13.so which crashes due to
        // C++ ODR conflicts with libLLVM.so.20.1 loaded in the same JVM.
        return parseStackMapJava(objectFile);
    }

    private static LLVMStackMapInfo parseStackMapJava(Path objectFile) {
        try {
            byte[] elfBytes = java.nio.file.Files.readAllBytes(objectFile);
            java.nio.ByteBuffer elf = java.nio.ByteBuffer.wrap(elfBytes)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);

            // ELF64 header fields
            long shoff = elf.getLong(40);          // section header table offset
            int shentsize = elf.getShort(58) & 0xFFFF; // section header entry size (=64)
            int shnum = elf.getShort(60) & 0xFFFF;     // number of section headers
            int shstrndx = elf.getShort(62) & 0xFFFF;  // .shstrtab section index

            // Offset of the section-name string table
            long shstrOff = elf.getLong((int) (shoff + (long) shstrndx * shentsize + 24));

            // Locate the three sections we need
            int stackmapIdx = -1, relaIdx = -1, symtabIdx = -1;
            for (int i = 0; i < shnum; i++) {
                int nameOff = elf.getInt((int) (shoff + (long) i * shentsize));
                String name = elfString(elfBytes, (int) (shstrOff + nameOff));
                if (".llvm_stackmaps".equals(name))      stackmapIdx = i;
                else if (".rela.llvm_stackmaps".equals(name)) relaIdx  = i;
                else if (".symtab".equals(name))          symtabIdx  = i;
            }
            if (stackmapIdx < 0) {
                throw new GraalError(".llvm_stackmaps section not found in: " + objectFile);
            }

            // Build the stackmap ByteBuffer (backed by the same array, zero-copy)
            long smOff  = elf.getLong((int) (shoff + (long) stackmapIdx * shentsize + 24));
            int  smSize = (int) elf.getLong((int) (shoff + (long) stackmapIdx * shentsize + 32));
            java.nio.ByteBuffer stackmapBuf = java.nio.ByteBuffer
                    .wrap(elfBytes, (int) smOff, smSize).slice()
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);

            // Build instruction-offset map from R_RISCV_ADD32 / R_RISCV_SUB32 pairs
            java.util.HashMap<Long, Integer> computed = new java.util.HashMap<>();
            if (relaIdx >= 0 && symtabIdx >= 0) {
                // Load symbol values (st_value at byte 8 within each 24-byte Elf64_Sym)
                long symOff  = elf.getLong((int) (shoff + (long) symtabIdx * shentsize + 24));
                long symSize = elf.getLong((int) (shoff + (long) symtabIdx * shentsize + 32));
                int symCount = (int) (symSize / 24);
                long[] symVals = new long[symCount];
                for (int i = 0; i < symCount; i++) {
                    symVals[i] = elf.getLong((int) (symOff + (long) i * 24 + 8));
                }

                // Iterate Elf64_Rela entries (24 bytes each: r_offset, r_info, r_addend)
                long relaSectionOff = elf.getLong((int) (shoff + (long) relaIdx * shentsize + 24));
                long relaSize       = elf.getLong((int) (shoff + (long) relaIdx * shentsize + 32));
                int  relaCount      = (int) (relaSize / 24);

                // RISC-V relocation type constants
                final int R_RISCV_ADD32 = 35; // 0x23
                final int R_RISCV_SUB32 = 39; // 0x27

                for (int i = 0; i < relaCount - 1; i++) {
                    long b1     = relaSectionOff + (long) i * 24;
                    long roff1  = elf.getLong((int) b1);
                    long rinfo1 = elf.getLong((int) (b1 + 8));
                    int  type1  = (int) (rinfo1 & 0xFFFFFFFFL);
                    int  sym1   = (int) (rinfo1 >>> 32);

                    if (type1 == R_RISCV_ADD32) {
                        long b2     = relaSectionOff + (long) (i + 1) * 24;
                        long roff2  = elf.getLong((int) b2);
                        long rinfo2 = elf.getLong((int) (b2 + 8));
                        int  type2  = (int) (rinfo2 & 0xFFFFFFFFL);
                        int  sym2   = (int) (rinfo2 >>> 32);

                        if (type2 == R_RISCV_SUB32 && roff2 == roff1) {
                            // instruction_offset = ADD32.sym.st_value - SUB32.sym.st_value
                            computed.put(roff1, (int) (symVals[sym1] - symVals[sym2]));
                            i++; // consumed the SUB32 entry
                        }
                    }
                }
            }

            // Provider: map lookup first; fall back to reading the field from the buffer
            // (correct for non-RISC-V targets and zero-offset records with no relocation)
            final java.util.HashMap<Long, Integer> finalMap = computed;
            final java.nio.ByteBuffer finalBuf = stackmapBuf;
            java.util.function.IntUnaryOperator provider =
                    offset -> finalMap.getOrDefault((long) offset, finalBuf.getInt(offset));

            return new LLVMStackMapInfo(stackmapBuf, provider);

        } catch (java.io.IOException e) {
            throw new GraalError("Failed to read ELF file " + objectFile + ": " + e.getMessage());
        }
    }

    private static String elfString(byte[] bytes, int start) {
        int end = start;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, start, end - start, java.nio.charset.StandardCharsets.UTF_8);
    }

    private LLVMStackMapInfo readStackMapSection(LLVMSectionIteratorRef sectionIterator, LLVMSectionIteratorRef relocationsSectionIterator) {
        Pointer stackMap = LLVM.LLVMGetSectionContents(sectionIterator).limit(LLVM.LLVMGetSectionSize(sectionIterator));
        LLVM.LLVMMoveToNextSection(relocationsSectionIterator);
        return new LLVMStackMapInfo(stackMap.asByteBuffer(), relocationsSectionIterator);
    }

    public void readStackMap(LLVMStackMapInfo info, CompilationResult compilation, ResolvedJavaMethod method, int id) {
        String methodSymbolName = SYMBOL_PREFIX + ((HostedMethod) method).getUniqueShortName();

        long startPatchpointID = compilation.getInfopoints().stream().filter(ip -> ip.reason == InfopointReason.METHOD_START).findFirst()
                        .orElseThrow(() -> new GraalError("No method start infopoint: " + methodSymbolName)).pcOffset;
        int totalFrameSize = NumUtil.safeToInt(info.getFunctionStackSize(startPatchpointID) + LLVMTargetSpecific.get().getCallFrameSeparation());
        compilation.setTotalFrameSize(totalFrameSize);
        stackMapDumper.startDumpingFunction(methodSymbolName, id, totalFrameSize);

        List<Infopoint> newInfopoints = new ArrayList<>();
        for (Infopoint infopoint : compilation.getInfopoints()) {
            if (infopoint instanceof Call) {
                Call call = (Call) infopoint;

                /* Optimizations might have duplicated some calls. */
                for (int actualPcOffset : info.getPatchpointOffsets(call.pcOffset)) {
                    SubstrateReferenceMap referenceMap = new SubstrateReferenceMap();
                    info.forEachStatepointOffset(call.pcOffset, actualPcOffset, referenceMap::markReferenceAtOffset);
                    stackMapDumper.dumpCallSite(call, actualPcOffset, referenceMap);
                    newInfopoints.add(new Call(call.target, actualPcOffset, call.size, call.direct, copyWithReferenceMap(call.debugInfo, referenceMap)));
                }
            }
        }
        stackMapDumper.endDumpingFunction();

        compilation.clearInfopoints();
        newInfopoints.forEach(compilation::addInfopoint);
    }

    private static DebugInfo copyWithReferenceMap(DebugInfo debugInfo, ReferenceMap referenceMap) {
        if (debugInfo == null) {
            return null;
        }

        DebugInfo newInfo = new DebugInfo(debugInfo.getBytecodePosition(), debugInfo.getVirtualObjectMapping());
        newInfo.setCalleeSaveInfo(debugInfo.getCalleeSaveInfo());
        newInfo.setReferenceMap(referenceMap);
        return newInfo;
    }

    public static final class LLVMTextSectionInfo {
        private final long codeSize;
        private final Map<Integer, String> offsetToSymbol = new TreeMap<>();
        private final Map<String, Integer> symbolToOffset = new HashMap<>();
        private final List<Integer> sortedMethodOffsets;

        private LLVMTextSectionInfo(LLVMSectionInfo<Long, SymbolOffset> sectionInfo) {
            this.codeSize = sectionInfo.sectionInfo;
            for (SymbolOffset symbolOffset : sectionInfo.symbolInfo) {
                if (LLVMTargetSpecific.get().isSymbolValid(symbolOffset.symbol)) {
                    offsetToSymbol.put(symbolOffset.offset, symbolOffset.symbol);
                    symbolToOffset.put(symbolOffset.symbol, symbolOffset.offset);
                }
            }
            this.sortedMethodOffsets = computeSortedMethodOffsets();
        }

        public long getCodeSize() {
            return codeSize;
        }

        public String getSymbol(int offset) {
            return offsetToSymbol.get(offset);
        }

        public int getOffset(String methodName) {
            return symbolToOffset.get(SYMBOL_PREFIX + methodName);
        }

        public int getNextOffset(int offset) {
            return sortedMethodOffsets.get(sortedMethodOffsets.indexOf(offset) + 1);
        }

        private List<Integer> computeSortedMethodOffsets() {
            List<Integer> sortedOffsets = offsetToSymbol.keySet().stream().distinct().sorted().collect(Collectors.toList());

            /*
             * Functions added by the LLVM backend have to be removed before computing function
             * offsets, because as they are not linked to a function known to Native Image, keeping
             * them would create gaps in the CodeInfoTable. Removing these offsets includes them as
             * part of the previously defined function instead. Stack walking will never see an
             * address belonging to one of these LLVM functions, as these are executing in native
             * mode, so this will not cause incorrect queries at runtime.
             */
            symbolToOffset.forEach((symbol, offset) -> {
                if (symbol.startsWith(SYMBOL_PREFIX + LLVMGenerator.JNI_WRAPPER_BASE_NAME)) {
                    sortedOffsets.remove(offset);
                }
            });

            sortedOffsets.add(NumUtil.safeToInt(codeSize));

            return sortedOffsets;
        }
    }
}
