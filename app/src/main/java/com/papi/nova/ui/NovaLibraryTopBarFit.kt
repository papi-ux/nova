package com.papi.nova.ui

/**
 * The landscape library strip in one row that never scrolls, and what gives way when its parts
 * do not fit.
 *
 * The strip used to force its row to 700 dp, or 980 dp with a continue card, times the font
 * scale, inside a horizontal scroll. On a Retroid Pocket 6 in landscape the strip is about 815 dp
 * wide, so every Grid or Compact library with Spaces scrolled sideways and pushed Options and
 * System past the right edge, and Stage did the same once text was enlarged. Now the composable
 * measures each part at the current font scale and this function decides, in a fixed order, what
 * to leave out until the row fits:
 *
 * 1. the card's eyebrow, which the action beside it already says (the title stays, with the
 *    eyebrow's line to wrap onto),
 * 2. the Space control's caption line (avatar, name and chevron stay),
 * 3. the host's status line,
 * 4. the card's cover,
 * 5. the card's title (its actions stay),
 * 6. the words of the Space's status badge (a dot stays),
 * 7. the Space name, which ellipsizes and at the last goes, leaving avatar and chevron,
 * 8. the host name, which ellipsizes down to [NovaTopBarWidths.identityFloor],
 * 9. End Session on the card, only when nothing else was enough.
 *
 * The card's words never ellipsize (R13). They are measured whole: the eyebrow on one line and the
 * title on the lines the strip's height leaves it, and they are shown at that width or not at all.
 *
 * A refused End's reason takes the eyebrow's place, and nothing else in the strip says it, so it is
 * never left out (XR3): it wraps onto the lines the strip has above the title, and where that is not
 * room enough, step 1 gives up the title instead and the reason takes the title's lines too.
 *
 * The result count and layout name are not in the strip any more. They read as a stray label in
 * the right-hand cluster (papi, 2026-09-16 21:27), and both already live in the Options sheet: the
 * count beside its title, the layout among its choices.
 *
 * Options and System never shrink or move: the composable measures the right cluster before the
 * left, so a residual mismatch squeezes the host side, never the menus.
 */

/**
 * [label] without its domain, for a place too narrow for the whole name: "living-room-gaming-pc"
 * for "living-room-gaming-pc.papi.miami". An address, or a name with no domain, stays whole.
 */
internal fun novaShortHostLabel(label: String): String {
    val trimmed = label.trim()
    if (NOVA_HOST_ADDRESS.matches(trimmed)) return trimmed
    val first = trimmed.substringBefore('.')
    return if (first.isNotEmpty() && first != trimmed && first.any { it.isLetter() }) first else trimmed
}

private val NOVA_HOST_ADDRESS = Regex("""^[0-9.]+$|^\[?[0-9a-fA-F:]+]?$""")

/** Below this the Space name reads as a stray letter, so it goes instead of ellipsizing further. */
internal const val NOVA_TOP_BAR_NAME_MIN = 24f

/** A step that cuts by exactly the overflow can leave a float residue; this much counts as fitting. */
private const val NOVA_TOP_BAR_EPSILON = 0.01f

/** The Space control's parts, in dp, as measured at the current font scale. */
internal data class NovaTopBarSpaceWidths(
    /** Surface padding, avatar, and the chevron with its gap: everything but the words. */
    val chrome: Float,
    /** The gap between the avatar and the words, when there are words. */
    val columnGap: Float,
    val caption: Float,
    val name: Float,
    /** The status badge with its padding, or 0 while the Space is ready. */
    val status: Float,
    /** The status as a dot, once the badge's words have to go. */
    val statusDot: Float,
    val statusGap: Float,
    /** The widest the control may grow, so a long name ellipsizes. */
    val cap: Float,
)

/** The continue card's parts, in dp. Its children sit [gap] apart after [padding]. */
internal data class NovaTopBarContinueWidths(
    val padding: Float,
    /** The cover square, or 0 when the card has no game to show. */
    val cover: Float,
    /**
     * The width the eyebrow and title need to be read whole: the eyebrow on one line, the title
     * on the lines the strip leaves under it. Narrower, the eyebrow goes first.
     */
    val textMin: Float,
    val gap: Float,
    val primary: Float,
    /** End Session, or 0 when the session is not this device's. */
    val secondary: Float,
    /** The width the title alone needs, on the lines the strip gives it once the eyebrow has gone. */
    val titleMin: Float = textMin,
    /**
     * The card's words are a refused End's reason, which stays whatever else gives way. Its
     * [titleMin] is then the reason alone, on every line the strip has.
     */
    val keepsText: Boolean = false,
)

/** Everything the strip holds, in dp, as measured at the current font scale. */
internal data class NovaTopBarWidths(
    /** The strip's width inside its horizontal padding. */
    val available: Float,
    val gap: Float,
    /** Rounding headroom, so a measurement a fraction short never squeezes a menu. */
    val slack: Float,
    val hostName: Float,
    /** "Polaris ready", or 0 when there is no status line. */
    val hostStatus: Float,
    val identityCap: Float,
    val identityFloor: Float,
    /** Null on a host without Spaces. */
    val space: NovaTopBarSpaceWidths?,
    /** Null when the strip draws no card: Stage, or nothing to act on now. */
    val continueCard: NovaTopBarContinueWidths?,
    val options: Float,
    val system: Float,
)

/** What the strip shows once it fits. */
internal data class NovaTopBarFit(
    val showSpaceCaption: Boolean = true,
    val showHostStatus: Boolean = true,
    val showContinueCover: Boolean = true,
    /** The card's eyebrow over its title; without it the title has the eyebrow's line too. */
    val showContinueEyebrow: Boolean = true,
    val showContinueText: Boolean = true,
    val compactSpaceStatus: Boolean = false,
    /** How wide the Space name may be; infinite while it keeps its natural width. */
    val spaceNameMax: Float = Float.POSITIVE_INFINITY,
    /** How wide the host identity may be; infinite while it keeps its natural width. */
    val identityMax: Float = Float.POSITIVE_INFINITY,
    val showContinueSecondary: Boolean = true,
    /** The Space control's width once fitted; infinite while nothing about it was trimmed. */
    val spaceWidth: Float = Float.POSITIVE_INFINITY,
    /**
     * How many lines the card's title may take: the lines the strip has room for under the
     * eyebrow, or without it. Set where the strip is measured; the fit itself only says what shows.
     */
    val continueTitleLines: Int = 1,
    /** How many lines the eyebrow may take: one, or a refused End's reason's lines. Set where measured. */
    val continueEyebrowLines: Int = 1,
) {
    val showSpaceName: Boolean get() = spaceNameMax >= NOVA_TOP_BAR_NAME_MIN
}

private fun visibleSpaceName(space: NovaTopBarSpaceWidths, fit: NovaTopBarFit): Float =
    if (fit.showSpaceName) minOf(space.name, fit.spaceNameMax) else 0f

private fun spaceStatusWidth(space: NovaTopBarSpaceWidths, fit: NovaTopBarFit): Float = when {
    space.status <= 0f -> 0f
    fit.compactSpaceStatus -> space.statusDot
    else -> space.status
}

/** The Space control's width under [fit]: chrome, then the wider of the caption and the name row. */
internal fun novaTopBarSpaceWidth(space: NovaTopBarSpaceWidths, fit: NovaTopBarFit): Float {
    val name = visibleSpaceName(space, fit)
    val status = spaceStatusWidth(space, fit)
    val nameRow = name + status + if (name > 0f && status > 0f) space.statusGap else 0f
    val column = maxOf(if (fit.showSpaceCaption) space.caption else 0f, nameRow)
    return minOf(space.cap, space.chrome + if (column > 0f) space.columnGap + column else 0f)
}

/** The continue card's width under [fit]; its title takes whatever is left beyond this. */
internal fun novaTopBarContinueWidth(card: NovaTopBarContinueWidths, fit: NovaTopBarFit): Float {
    val parts = buildList {
        if (fit.showContinueCover && card.cover > 0f) add(card.cover)
        if (fit.showContinueText) add(if (fit.showContinueEyebrow) card.textMin else card.titleMin)
        add(card.primary)
        if (fit.showContinueSecondary && card.secondary > 0f) add(card.secondary)
    }
    return card.padding + parts.sum() + card.gap * (parts.size - 1)
}

/** The host identity's width under [fit]: the wider of its two lines, capped. */
internal fun novaTopBarIdentityWidth(widths: NovaTopBarWidths, fit: NovaTopBarFit): Float =
    minOf(widths.identityCap, fit.identityMax, maxOf(widths.hostName, if (fit.showHostStatus) widths.hostStatus else 0f))

/** The width the whole row asks for under [fit], slack included. */
internal fun novaTopBarRequiredWidth(widths: NovaTopBarWidths, fit: NovaTopBarFit): Float {
    // Left: the host, then the continue card or an empty spacer.
    val middle = widths.continueCard?.let { novaTopBarContinueWidth(it, fit) } ?: 0f
    val left = novaTopBarIdentityWidth(widths, fit) + widths.gap + middle
    // Right: what can be pressed, ending in Options and System.
    val right = buildList {
        widths.space?.let { add(novaTopBarSpaceWidth(it, fit)) }
        add(widths.options)
        add(widths.system)
    }
    return left + widths.gap + right.sum() + widths.gap * (right.size - 1) + widths.slack
}

/** What is still over once [fit] is applied; 0 when the row fits. */
internal fun novaTopBarOverflow(widths: NovaTopBarWidths, fit: NovaTopBarFit): Float =
    maxOf(0f, novaTopBarRequiredWidth(widths, fit) - widths.available)

/**
 * The continue card's words, so the strip can measure the card before it draws it. With [refusal],
 * the eyebrow is why an End was refused.
 */
internal data class NovaTopBarContinue(
    val eyebrow: String,
    val title: String,
    val actionLabel: String,
    val secondaryActionLabel: String?,
    val hasCover: Boolean,
    val refusal: Boolean = false,
)

/**
 * The continue card's words for [hero], by the same rules the card draws with: a cover when there
 * is a game to show, and End Session only when the hero offers that action.
 */
internal fun NovaLibraryHeroState.topBarContinue(): NovaTopBarContinue = NovaTopBarContinue(
    eyebrow = eyebrow,
    title = title,
    actionLabel = actionLabel,
    secondaryActionLabel = secondaryActionLabel?.takeIf { secondaryAction != null },
    hasCover = game != null,
    refusal = endStatus is NovaLibraryEndStatus.Failed,
)

/** Leave parts out, in the documented order, and stop the moment the row fits. */
internal fun novaLibraryTopBarFit(widths: NovaTopBarWidths): NovaTopBarFit {
    val fit = fitTopBar(widths)
    // Once the name has been cut the control needs an exact width so the name ellipsizes there.
    val space = widths.space ?: return fit
    return if (fit.spaceNameMax.isFinite()) fit.copy(spaceWidth = novaTopBarSpaceWidth(space, fit) + 1f) else fit
}

private fun fitTopBar(widths: NovaTopBarWidths): NovaTopBarFit {
    var fit = NovaTopBarFit()
    fun over() = novaTopBarOverflow(widths, fit)
    val steps: List<(NovaTopBarFit) -> NovaTopBarFit> = listOf(
        { it.copy(showContinueEyebrow = false) },
        { it.copy(showSpaceCaption = false) },
        { it.copy(showHostStatus = false) },
        { it.copy(showContinueCover = false) },
        // A refused End's reason is never left out; what gives way after it does instead.
        { if (widths.continueCard?.keepsText == true) it else it.copy(showContinueText = false) },
        { it.copy(compactSpaceStatus = true) },
    )
    for (step in steps) {
        if (over() <= NOVA_TOP_BAR_EPSILON) return fit
        fit = step(fit)
    }
    val space = widths.space
    if (space != null && over() > NOVA_TOP_BAR_EPSILON) {
        fit = fit.copy(spaceNameMax = maxOf(0f, visibleSpaceName(space, fit) - over()))
    }
    if (over() > NOVA_TOP_BAR_EPSILON) {
        val identity = novaTopBarIdentityWidth(widths, fit)
        val floor = minOf(widths.hostName, widths.identityFloor)
        fit = fit.copy(identityMax = maxOf(floor, identity - over()))
    }
    if (over() > NOVA_TOP_BAR_EPSILON && (widths.continueCard?.secondary ?: 0f) > 0f) {
        fit = fit.copy(showContinueSecondary = false)
    }
    return fit
}
