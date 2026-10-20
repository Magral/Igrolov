package ru.igrolov.app

/** Preserve the search's exact game identity; recover legacy cards only from an unambiguous known title. */
object TrackedGameIdentity {
    data class Identity(val canonical:String,val appId:Int=0,val confirmed:Boolean=true)
    fun resolve(tracked:Tracked,title:String,known:List<GameSearchResolver.Candidate>):Identity {
        if(tracked.canonical.isNotBlank())return Identity(tracked.canonical,tracked.steamAppId)
        val normalized=" ${GameSearchResolver.normalized(title)} "
        data class Match(val game:GameSearchResolver.Candidate,val start:Int,val end:Int)
        val matches=known.distinctBy {it.appId}.mapNotNull {candidate ->
            (candidate.aliases+GameSearchResolver.aliases(candidate.appId,candidate.name)+candidate.name)
                .map {GameSearchResolver.normalized(it)}.filter {it.isNotBlank()}
                .mapNotNull {name->
                    val start=normalized.indexOf(" $name ")
                    if(start<0)null else Match(candidate,start,start+name.length)
                }.maxByOrNull {it.end-it.start}
        }
        // A longer overlapping title identifies the sequel; separate game names indicate ambiguity.
        val distinct=matches.filter {entry->matches.none {other->
            other.game.appId!=entry.game.appId && other.start<=entry.start && other.end>=entry.end && (other.end-other.start)>(entry.end-entry.start)
        }}
        val match=distinct.singleOrNull()?.game
        return if(match!=null)Identity(match.name,match.appId) else Identity(title,confirmed=false)
    }
}
