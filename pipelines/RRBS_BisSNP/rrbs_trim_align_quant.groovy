load "sample.groovy"
load "system.groovy"

///make list of all input files
makefilelist = {
	doc 	"Make a list of files processed"
	exec 	""">filelist.txt"""
	for (i in inputs) {
			exec 	"""echo $i >>filelist.txt"""
	}
}

//run fastqc on untrimmed
fastqc_pretrim = {
    doc 	"Run FASTQC to generate QC metrics for the untrimmed reads"
    output.dir = "${BASEDIR}/fastqc/${input.fastq}/pretrim/"
    transform('.fastq')  to('_fastqc.zip')  {
			exec "fastqc -o $output.dir $input.fastq"
    }
}

// Trim
trim_galore = {
	doc 	"Trim adapters and low quality bases from all reads"
    	output.dir = "${BASEDIR}"
	from("fastq") {
		transform('.fastq') to ('.trimmed.fq'){
			exec 	"""
				trim_galore ${RRBSVAR} ${DIRECTIONVAR} 
				--fastqc 
				--fastqc_args "--outdir ${BASEDIR}/fastqc/${input.fastq}/posttrim" 
				--adapter ${ADAPTER} 
				--length ${MINTRIMMEDLENGTH} 
				--quality ${QUALITY} $input.fastq
				"""
		}	
	}
}	


// Align
@Transform("fq_bismark.sam")
bismarkalign = {
	doc 	"Align to genome with Bismark"
	from('trimmed.fq') {	
		transform('trimmed.fq') to ('trimmed.fq_bismark.sam') {
			exec 	"""
				bismark -n 1 --unmapped ${DIRECTIONVAR} ${REFERENCEGENOMEDIR}/ $input
				"""	
		}
	}
}

//BSeQC
bseqc = {
	doc 	"Repair methylation biases with BSeQC"
	transform("fq_bismark.sam") to ("fq_bismark_${input}_bseqc_filter.sam") {
		exec	"""
			${BSEQCBIN} 
			mbias
			-l ${READLENGTH} 
			-r ${REFERENCEGENOMEDIR}/genome.fa 
			-n ${input}_bseqc 
			-s $input.sam
			"""
	}
}

//cleanupfilename
dedupfilename= {
	doc "Simplify filenames"
    def outputs = [
        file(input.sam).name.replaceAll('^.*fq_bismark_', ''),
    ]
    produce(outputs) {
	exec	"""cp $input $output""" 
	}
}
// make bam 
@Transform("bam")
makebam = {
	doc "Compress SAM file to BAM file"
	exec 	"""
		java -Xmx2g -Djava.io.tmpdir=${TMPDIR} -jar ${PICARDDIR}/SamFormatConverter.jar INPUT=$input OUTPUT=$output
		"""
}

// add read groups
@Filter("RG")
addreadgroups = {
	doc "Add readgroups to BAM file as part of GATK preprocessing"
	exec 	"""
		java -Xmx2g -Djava.io.tmpdir=${TMPDIR} 
		-jar ${PICARDDIR}/AddOrReplaceReadGroups.jar
		INPUT=$input
		OUTPUT=$output
		RGLB=RRBS_LIB
		RGPL=Illumina
		RGPU=R1
		RGID=$input
		RGSM=$input
		CREATE_INDEX=true 
		VALIDATION_STRINGENCY=SILENT 
		SORT_ORDER=coordinate
		"""
}

// reorder_contigs
@Filter("RO")
reorder_contigs = {
	doc "Reorder contigs of BAM file as part of GATK preprocessing"
	exec 	"""
	 	java -Xmx2g -Djava.io.tmpdir=${TMPDIR} -jar ${PICARDDIR}/ReorderSam.jar 
	 	INPUT=$input
	  	OUTPUT=$output
	  	REFERENCE=${REFERENCEGENOMEDIR}/genome.fa
	  	"""
}

// Remove duplicates
@Filter("deduped")
dedupe = {
	doc "Remove duplicates from BAM file as part of GATK preprocessing"
	exec 	"""
	      	java -Xmx2g -jar ${PICARDDIR}/MarkDuplicates.jar           
			MAX_FILE_HANDLES_FOR_READ_ENDS_MAP=1000
			METRICS_FILE=out.metrics 
	        REMOVE_DUPLICATES=true 
	        ASSUME_SORTED=true  
	        VALIDATION_STRINGENCY=LENIENT 
	        INPUT=$input 
	        OUTPUT=$output
			"""
}
		
// indexbam
indexbam = {
	doc "Index BAM file"
	transform(".bam") to (".bam.bai") {
		exec 	"""samtools index $input"""
	}
}
// count_covars
@Transform("recal1.csv")
count_covars = {
	from(".bam") {
		exec 	"""
				java -Xmx10g -jar ${BISSNPJAR} 
			-R ${REFERENCEGENOMEDIR}/genome.fa 
			-I $input 
			-T BisulfiteCountCovariates 
			-knownSites $SNP135
			-cov ReadGroupCovariate 
			-cov QualityScoreCovariate 
			-cov CycleCovariate 
			-recalFile $output
			-nt 8
			"""
	}
}			
// write_recal_BQscore_toBAM
write_recal_BQscore_toBAM = {
	from("bam","csv") {
		transform("bam") to ("recal1.bam") {
			exec 	"""
				java -Xmx10g -jar $BISSNPJAR 
				-R ${REFERENCEGENOMEDIR}/genome.fa 
				-I $input1 
				-o $output 
				-T BisulfiteTableRecalibration 
				-recalFile $input2 
				-maxQ 60
				"""
		}
	}
}

// call_meth
call_meth = {
	transform("rawcpg.vcf", "rawsnp.vcf"){
			exec "java -Xmx10g -jar $BISSNPJAR -R ${REFERENCEGENOMEDIR}/genome.fa -T BisulfiteGenotyper -I $input -vfn1 $output1 -vfn2 $output2 -stand_call_conf 20 -stand_emit_conf 0 -mmq 30 -mbq 0","BisSNPcaller"
	}
}

run { makefilelist + "%fastq" * [ fastqc_pretrim +trim_galore +  bismarkalign + bseqc + dedupfilename + makebam + addreadgroups + reorder_contigs + dedupe + indexbam + count_covars + write_recal_BQscore_toBAM + call_meth]}
//run {"%.fastq" * [ makefilelist + setupdirs + fastqc + trim_galore + bismarkalign + makebam + addreadgroups + reorder_contigs + dedupe + indexbam + count_covars + write_recal_BQscore_toBAM + call_meth]}
