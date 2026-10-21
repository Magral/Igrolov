package ru.igrolov.app

import java.text.Normalizer
import java.util.Locale

/** Fuzzy matching chooses Steam suggestions, never relaxes offer eligibility. */
object GameSearchResolver {
    data class Candidate(val appId: Int, val name: String, val aliases: List<String> = emptyList())
    val seeds = listOf(
        Candidate(281990,"Stellaris",listOf("Стелларис")),
        Candidate(289070,"Sid Meier's Civilization VI",listOf("Civilization VI","Civilization 6","Civ 6","Цивилизация 6","Цива 6")),
        Candidate(8930,"Sid Meier's Civilization V",listOf("Civilization V","Civilization 5","Civ 5","Цивилизация 5")),
        Candidate(255710,"Cities: Skylines",listOf("Cities Skylines","Cities Skylines 1","Ситис Скайлайнс")),
        Candidate(949230,"Cities: Skylines II",listOf("Cities Skylines 2","Ситис Скайлайнс 2")),
        Candidate(1222670,"The Sims 4",listOf("Sims 4","Симс 4")),
        // Steam's Director's Cut app keeps marketplace listings under its original release title.
        Candidate(2863680,"ZERO PARADES: Director's Cut",listOf("ZERO PARADES: For Dead Spies")),
        Candidate(1091500,"Cyberpunk 2077",listOf("Киберпанк 2077")),
        Candidate(990080,"Hogwarts Legacy",listOf("Хогвартс","Наследие Хогвартса")),
        Candidate(730,"Counter-Strike 2",listOf("CS2","КС2"))
    )
    private val roman=mapOf("ii" to "2","iii" to "3","iv" to "4","v" to "5","vi" to "6","vii" to "7","viii" to "8","ix" to "9","x" to "10")
    fun normalized(value: String): String = Normalizer.normalize(value,Normalizer.Form.NFD).lowercase(Locale.ROOT)
        .replace('ё','е').replace(Regex("\\p{M}+"),"").replace(Regex("['’‘`´]"),"")
        .replace(Regex("[^\\p{L}\\p{N}]+")," ").trim().split(Regex("\\s+"))
        .joinToString(" ") {roman[it] ?: it}
    fun aliases(appId: Int, name: String): List<String> = (seeds.firstOrNull {it.appId==appId}?.aliases.orEmpty() +
        listOf(name.replace(Regex("[™®©]"),""),name.replace(Regex("(?i)^sid meier['’]s\\s+"),""))).distinct()
    private fun numbers(value: String)=Regex("[0-9]+").findAll(normalized(value)).map {it.value}.toList()
    private fun distance(a: String,b: String):Int {
        if(kotlin.math.abs(a.length-b.length)>3)return 100
        val matrix=Array(a.length+1){IntArray(b.length+1)}
        for(i in 0..a.length)matrix[i][0]=i
        for(j in 0..b.length)matrix[0][j]=j
        for(i in 1..a.length)for(j in 1..b.length) {
            matrix[i][j]=minOf(matrix[i-1][j]+1,matrix[i][j-1]+1,matrix[i-1][j-1]+if(a[i-1]==b[j-1])0 else 1)
            if(i>1 && j>1 && a[i-1]==b[j-2] && a[i-2]==b[j-1])matrix[i][j]=minOf(matrix[i][j],matrix[i-2][j-2]+1)
        }
        return matrix[a.length][b.length]
    }
    fun score(query: String,candidate: Candidate):Double {
        val q=normalized(query)
        if(q.length<2)return 0.0
        val names=(candidate.aliases+aliases(candidate.appId,candidate.name)+candidate.name).distinct()
        return names.maxOfOrNull { name ->
            val n=normalized(name)
            when {
                numbers(q).isNotEmpty() && numbers(q)!=numbers(n) -> 0.0
                q==n || q.replace(" ","")==n.replace(" ","") -> 1.0
                q.length>=3 && n.startsWith(q) -> 0.88
                q.length>=4 && distance(q,n)<=if(q.length>=10)2 else 1 -> 0.85-distance(q,n)*0.04
                else -> 0.0
            }
        } ?: 0.0
    }
    fun rank(query: String,candidates: List<Candidate>): List<Candidate> = candidates.distinctBy {it.appId}
        .map {it to score(query,it)}.filter {it.second>0}.sortedByDescending {it.second}.map {it.first}.take(8)
    fun exact(query: String,candidates: List<Candidate>)=candidates.distinctBy {it.appId}.filter {score(query,it)==1.0}.singleOrNull()
    private fun keyboard(value: String):String {
        val ru="йцукенгшщзхъфывапролджэячсмитьбю";val en="qwertyuiop[]asdfghjkl;'zxcvbnm,."
        return value.lowercase(Locale.ROOT).map { c ->ru.indexOf(c).let {if(it>=0)en[it] else c} }.joinToString("")
    }
    fun queries(query: String,local: List<Candidate> = emptyList()):List<String> {
        val matches=rank(query,local+seeds)
        return (listOfNotNull(matches.firstOrNull()?.name,query.trim(),keyboard(query).takeIf {it!=query.lowercase(Locale.ROOT)})).distinct().filter {it.length>=2}.take(3)
    }
    fun suggest(query:String,local:List<Candidate>,active:()->Boolean,fetch:(String)->List<Candidate>):List<Candidate> {
        val results=mutableListOf<Candidate>()
        for(term in queries(query,local)) {
            if(!active())return emptyList()
            results.addAll(runCatching {fetch(term)}.getOrDefault(emptyList()))
            if(exact(query,results)!=null)break
        }
        if(!active())return emptyList()
        // Live Steam suggestions may be useful even when their title is a translated match.
        val ranked=rank(query,results+local+seeds)
        return (ranked+results).distinctBy {it.appId}.take(8)
    }
}
