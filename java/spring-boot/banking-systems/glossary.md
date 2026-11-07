# Banking Glossary

Plain-English definitions of the banking terms used across the series. The guides link here the first time they use a term.

Definitions are deliberately short. Where a term has a precise accounting or regulatory meaning, the definition gives the version you need to work on the software and points out where reality is more complicated.

The **Covered in** column says which tutorial teaches the idea properly.

---

## Accounts and bookkeeping

| Term | Meaning | Covered in |
|---|---|---|
| <a id="account"></a>**Account** | A named bucket of money in the ledger. Customers have accounts (current, savings), and so does the bank itself (cash, fees, interest paid). | Core ledger 01 |
| <a id="asset"></a>**Asset** | Something the bank *owns* or is owed: its cash, the loans it has made. | Core ledger 01 |
| <a id="liability"></a>**Liability** | Something the bank *owes*. Your current account balance is a liability **to the bank**, because the bank owes you that money. | Core ledger 01 |
| <a id="balance-sheet"></a>**Balance sheet** | The bank's list of assets and liabilities (plus equity). Assets = liabilities + equity, always. | Core ledger 02 |
| <a id="debit"></a>**Debit** | One of the two sides of every posting. A debit *increases* an asset and *decreases* a liability. It does **not** simply mean "money out". | Core ledger 02 |
| <a id="credit"></a>**Credit** | The other side. A credit *decreases* an asset and *increases* a liability. When your bank "credits your account", the bank's liability to you goes up. | Core ledger 02 |
| <a id="normal-balance"></a>**Normal balance** | The side (debit or credit) that makes an account's balance go up. Debit for assets, credit for liabilities. | Core ledger 02 |
| <a id="double-entry"></a>**Double-entry bookkeeping** | Every movement of money is recorded at least twice, as debits and credits that add up to the same amount. Money is never created or destroyed, only moved. | Core ledger 02 |
| <a id="journal-entry"></a>**Journal entry** | One business event (a deposit, a transfer) recorded in the ledger. Made of two or more postings that balance. Also called a *transaction*, but that word is overloaded in software. | Core ledger 02 |
| <a id="posting"></a>**Posting** | One line of a journal entry: "debit account X by £Y" or "credit account Z by £Y". Sometimes called an *entry line* or *leg*. | Core ledger 02 |
| <a id="general-ledger"></a>**General ledger (GL)** | The bank's master set of accounts and postings, used for financial reporting. | Core ledger 02 |
| <a id="sub-ledger"></a>**Sub-ledger** | A detailed ledger for one area (customer accounts, cards, loans) that rolls up into the general ledger as totals. Most of what developers call "the ledger" is really a sub-ledger. | Core ledger 02 |
| <a id="internal-account"></a>**Internal account** | An account the bank owns for its own bookkeeping (cash, suspense, fees). Customers never see these. | Core ledger 02 |
| <a id="suspense-account"></a>**Suspense account** | An internal account where money sits temporarily when the bank can't yet tell where it belongs. Ops teams work to keep it near zero. | Reconciliation 04 |

## Balances

| Term | Meaning | Covered in |
|---|---|---|
| <a id="ledger-balance"></a>**Ledger balance** | The balance from everything actually posted to the account. Also called the *book balance* or *current balance*. | Core ledger 02 |
| <a id="available-balance"></a>**Available balance** | What the customer can spend right now: the ledger balance, minus holds (such as pending card payments), plus any arranged overdraft. | Card authorisation 01 |
| <a id="cached-balance"></a>**Cached (stored) balance** | A balance column kept up to date as postings are written, so it's fast to read. It must always equal the balance derived from the postings. | Core ledger 02 |
| <a id="derived-balance"></a>**Derived balance** | The balance worked out from scratch by adding up the postings. It's the source of truth. | Core ledger 02 |
| <a id="running-balance"></a>**Running balance** | The balance after each line of a statement. | Core ledger 04 |
| <a id="overdraft"></a>**Overdraft** | A negative balance on a current account. *Arranged*: agreed in advance, with a limit. *Unarranged*: not agreed, usually discouraged or blocked. | Core ledger 03 |
| <a id="hot-account"></a>**Hot account** | An account touched by a large share of all transactions, such as the bank's cash or a big merchant's account. It becomes a locking bottleneck. | Core ledger 03 |

## Correcting and proving

| Term | Meaning | Covered in |
|---|---|---|
| <a id="reversal"></a>**Reversal** | A new journal entry that exactly cancels an earlier one by flipping every debit and credit. Ledgers never edit or delete. They reverse. | Core ledger 04 |
| <a id="audit-trail"></a>**Audit trail** | A complete, unchangeable record of what happened, when, and why. Regulators and auditors rely on it. | Core ledger 04 |
| <a id="statement"></a>**Statement** | A customer-facing list of the postings on an account over a period, with running balances. | Core ledger 04 |
| <a id="idempotency"></a>**Idempotency** | Doing the same request twice has the same effect as doing it once. Essential when networks time out and clients retry. | Core ledger 04, Payments 03, 05 |
| <a id="idempotency-key"></a>**Idempotency key** | A unique value the client sends with a request (usually a UUID in an `Idempotency-Key` header) so the server can recognise a retry. | Core ledger 04, Payments 03 |
| <a id="reconciliation"></a>**Reconciliation** | Checking that two records of the same money agree, such as the bank's ledger against a payment scheme's settlement report, then investigating the differences ("breaks"). | Reconciliation 01 |
| <a id="break"></a>**Break** | An item on one record with no match on the other. It's either a timing difference or a genuine error. | Reconciliation 03 |
| <a id="timing-difference"></a>**Timing difference** | A break that both sides will agree on later, because they booked the item on different days. | Reconciliation 05 |
| <a id="matching-tolerance"></a>**Matching tolerance** | The agreed allowance (a few days, a few pence) within which two items still count as a match. | Reconciliation 03 |
| <a id="ageing"></a>**Ageing** | How long a break has been open, grouped into age buckets and escalated as it gets older. | Reconciliation 04 |
| <a id="exception-report"></a>**Exception report** | The list of open breaks, with their ages and totals, that operations teams work from. | Reconciliation 04 |
| <a id="booking-date"></a>**Booking date / value date** | *Booking date*: when the posting was recorded. *Value date*: when the money counts for interest purposes. They can differ, for example for a payment made at the weekend. | Loans 02 |
| <a id="end-of-day"></a>**End of day (EOD)** | The nightly batch window when banks accrue interest, apply fees, produce statements and close the books for the day. | Loans 03 |

## Payments and cards

| Term | Meaning | Covered in |
|---|---|---|
| <a id="sort-code"></a>**Sort code + account number** | The UK way to identify a bank account: a 6-digit sort code (bank and branch) and an 8-digit account number. | Payments 01 |
| <a id="faster-payments"></a>**Faster Payments (FPS)** | The UK's real-time payment scheme for most transfers between banks, operated by Pay.UK. | Payments 01 |
| <a id="bacs"></a>**Bacs** | A UK scheme for bulk payments (salaries, Direct Debits) that takes three working days. | Payments 01 |
| <a id="chaps"></a>**CHAPS** | A UK scheme for high-value, same-day payments, settled through the Bank of England. | Payments 01 |
| <a id="clearing"></a>**Clearing** | Exchanging and confirming payment instructions between banks: agreeing *who owes whom*. | Payments 01 |
| <a id="settlement"></a>**Settlement** | Actually moving the money between banks, usually through their accounts at the central bank. It comes after clearing. | Payments 01 |
| <a id="payment-scheme"></a>**Payment scheme** | The rules and shared infrastructure that banks use to clear payments with each other, such as Faster Payments, Bacs and CHAPS. | Payments 01 |
| <a id="rtgs"></a>**RTGS** | *Real-time gross settlement*: each payment settles individually and immediately across central bank accounts. The Bank of England runs the UK's RTGS service, and CHAPS settles this way. | Payments 01 |
| <a id="deferred-net-settlement"></a>**Deferred net settlement** | Banks' positions are netted and settled periodically in cycles, rather than payment by payment. Faster Payments and Bacs settle this way. | Payments 01 |
| <a id="prefunding"></a>**Prefunding** | Settlement participants in Faster Payments and Bacs hold cash at the Bank of England to cover their maximum net position, so settlement can't fail for lack of funds. | Payments 01 |
| <a id="reason-code"></a>**Reason code** | An ISO 20022 status reason explaining a rejection, for example `AC01` (incorrect account number) or `AM04` (insufficient funds). | Payments 01, 05 |
| <a id="payment-return"></a>**Payment return** | Money sent back after the receiving bank has accepted a payment. It's a new payment that references the original, not an undo of the first one. | Payments 05 |
| <a id="confirmation-of-payee"></a>**Confirmation of Payee (CoP)** | A check that the name a customer typed matches the name on the payee's account, done before the payment is sent. The rules are set by Pay.UK. | Payments 01 |
| <a id="iso-20022"></a>**ISO 20022** | The international standard for financial messages (XML, increasingly JSON). For example, `pacs.008` is a customer credit transfer and `camt.053` is a bank statement. | Payments 01, Reconciliation 01 |
| <a id="nostro-vostro"></a>**Nostro / vostro** | "Ours" and "yours". A *nostro* is our bank's account held at another bank. From the other bank's side, the same account is a *vostro*. | Reconciliation 01 |
| <a id="correspondent-bank"></a>**Correspondent bank** | The bank that holds another bank's nostro account and sends it statements. | Reconciliation 01 |
| <a id="authorisation"></a>**Authorisation** | The card network asking the issuing bank, in real time, "can this card spend £X here?" The answer is approve or decline. | Card authorisation 02 |
| <a id="hold"></a>**Hold** | Money reserved by an approved authorisation but not yet posted. It reduces the available balance, not the ledger balance. | Card authorisation 02 |
| <a id="four-party-model"></a>**Four-party model** | How card payments are organised: cardholder, merchant, acquirer and issuer, connected by a card scheme. | Card authorisation 01 |
| <a id="issuer"></a>**Issuer** | The cardholder's bank. It issues the card, decides each authorisation and pays for approved payments. | Card authorisation 01 |
| <a id="acquirer"></a>**Acquirer** | The merchant's bank or payment provider. It connects the merchant to the card schemes and pays the merchant. | Card authorisation 01 |
| <a id="card-scheme"></a>**Card scheme** | The network (such as Visa or Mastercard) that routes card messages, sets the rules and works out who owes whom. | Card authorisation 01 |
| <a id="response-code"></a>**Response code** | The two-digit answer to an authorisation request: `00` approves, and anything else declines (for example `51`, insufficient funds). | Card authorisation 02 |
| <a id="stand-in-processing"></a>**Stand-in processing** | The card scheme deciding an authorisation on the issuer's behalf when the issuer doesn't answer in time. | Card authorisation 02 |
| <a id="authorisation-reversal"></a>**Authorisation reversal** | Releasing all or part of a hold before clearing. No money has moved yet, so nothing is posted. | Card authorisation 03 |
| <a id="presentment"></a>**Presentment** | The clearing record that finalises a card payment. The issuer posts it and releases the matching hold. The amount can differ from the authorised one (fuel, tips). | Card authorisation 04 |
| <a id="late-presentment"></a>**Late presentment** | A presentment that arrives after its hold has expired. It must still be posted. | Card authorisation 05 |
| <a id="pan"></a>**PAN** | *Primary account number*: the long number on a card. Systems avoid handling it by using tokens instead. | Card authorisation 01 |
| <a id="pci-dss"></a>**PCI DSS** | The card industry's security standard for any system that stores, processes or transmits card data. | Card authorisation 01 |
| <a id="mcc"></a>**Merchant category code (MCC)** | A four-digit code (ISO 18245) describing a merchant's type of business, such as fuel or restaurants. | Card authorisation 02 |

## Lending

| Term | Meaning | Covered in |
|---|---|---|
| <a id="principal"></a>**Principal** | The amount borrowed, and later the amount still owed, not counting interest. | Loans 01 |
| <a id="apr"></a>**APR** | *Annual percentage rate of charge*: the regulated, annualised total cost of credit, including most compulsory fees. It's the figure lenders must use to let customers compare loans. | Loans 01 |
| <a id="amortisation"></a>**Amortisation** | Repaying a loan through regular instalments that each cover that period's interest plus some principal. The schedule shows the split. | Loans 01 |
| <a id="day-count-convention"></a>**Day-count convention** | The rule for turning an annual rate into daily interest, for example *Actual/365 Fixed* (actual days, divided by 365). | Loans 01 |
| <a id="accrual"></a>**Accrual** | Interest earned day by day and recorded before it's charged or paid. | Loans 03 |
| <a id="arrears"></a>**Arrears** | Payments that have fallen due and haven't been paid. | Loans 04 |
| <a id="days-past-due"></a>**Days past due (DPD)** | Days since the oldest unpaid amount fell due. It drives arrears buckets, and 90+ days is a standard default trigger. | Loans 04 |

## Open Banking

| Term | Meaning | Covered in |
|---|---|---|
| <a id="open-banking"></a>**Open Banking** | The UK's standardised APIs that let regulated third parties access accounts with the customer's consent, implementing PSD2. The largest banks (the CMA9) were required to build them by the CMA Order. | Open Banking API 01 |
| <a id="aspsp"></a>**ASPSP** | *Account servicing payment service provider*: the bank that holds the customer's account and exposes the Open Banking APIs. | Open Banking API 01 |
| <a id="tpp"></a>**TPP (AISP / PISP)** | *Third-party provider*: a regulated firm using Open Banking. An AISP reads account information. A PISP initiates payments. | Open Banking API 01 |
| <a id="psu"></a>**PSU** | *Payment service user*: the customer. | Open Banking API 01 |
| <a id="consent"></a>**Consent** | The bank's record of what a customer allowed a TPP to access (permissions, accounts, expiry), with its own status lifecycle. | Open Banking API 03 |
| <a id="sca"></a>**SCA** | *Strong customer authentication*: proving who you are with at least two of something you know, something you have, and something you are. | Open Banking API 02 |
| <a id="fapi"></a>**FAPI** | The OpenID Foundation's security profiles that harden OAuth 2.0 for financial-grade APIs. | Open Banking API 02 |

## Regulation and risk

| Term | Meaning | Covered in |
|---|---|---|
| <a id="kyc"></a>**KYC** | *Know Your Customer*: verifying who a customer is before (and while) banking them. | Customer onboarding 01 |
| <a id="cdd"></a>**CDD** | *Customer due diligence*: identifying and verifying a customer, and understanding the purpose of the relationship. | Customer onboarding 01 |
| <a id="risk-based-approach"></a>**Risk-based approach** | Scaling due diligence to the assessed risk of each customer, rather than treating everyone identically. | Customer onboarding 02 |
| <a id="sdd"></a>**SDD** | *Simplified due diligence*: reduced measures for customers assessed as low risk. It's still due diligence. | Customer onboarding 02 |
| <a id="edd"></a>**EDD** | *Enhanced due diligence*: extra checks required for higher-risk customers, such as PEPs. | Customer onboarding 02 |
| <a id="idv"></a>**ID&V** | *Identity verification*: confirming a customer is who they say they are, electronically or from documents. | Customer onboarding 02 |
| <a id="pep"></a>**PEP** | *Politically exposed person* (plus their family members and known close associates). Banking a PEP needs EDD and senior management approval. | Customer onboarding 02, 03 |
| <a id="sanctions-screening"></a>**Sanctions screening** | Checking customers against the UK Sanctions List (and others). Possible matches are reviewed, and confirmed matches are frozen and reported to OFSI. | Customer onboarding 02 |
| <a id="ongoing-monitoring"></a>**Ongoing monitoring** | Keeping due diligence up to date and scrutinising activity throughout the relationship, not just at onboarding. | Customer onboarding 05 |
| <a id="aml"></a>**AML** | *Anti-Money Laundering*: the rules and monitoring that detect and report money laundering. | Fraud monitoring 01, Customer onboarding 02 |
| <a id="psd2"></a>**PSD2** | The EU's second Payment Services Directive, which requires banks to open account access to authorised third parties. The UK implemented it as **Open Banking**. | Open Banking API 01 |
| <a id="pii"></a>**PII** | Personally identifiable information: names, addresses, dates of birth, account numbers. Protected by GDPR. | Customer onboarding 04, 05 |

## Financial crime

| Term | Meaning | Covered in |
|---|---|---|
| <a id="transaction-monitoring"></a>**Transaction monitoring** | Checking customers' transactions against rules or models to spot fraud or money laundering. | Fraud monitoring 01 |
| <a id="app-fraud"></a>**APP fraud** | *Authorised push payment* fraud: a criminal tricks the customer into sending a payment themselves. | Fraud monitoring 01 |
| <a id="money-mule"></a>**Money mule** | Someone whose account is used to receive and pass on stolen money. | Fraud monitoring 01 |
| <a id="alert"></a>**Alert** | A record that a transaction matched a monitoring rule, for an analyst to review. | Fraud monitoring 02 |
| <a id="false-positive"></a>**False positive** | An alert on an innocent transaction. Too many of them swamp the analysts. | Fraud monitoring 02 |
| <a id="velocity"></a>**Velocity** | How many transactions happen in a time window. A sudden burst is a classic fraud signal. | Fraud monitoring 02 |
| <a id="structuring"></a>**Structuring** | Splitting a large sum into smaller amounts to avoid attention. Also called *smurfing*. | Fraud monitoring 02 |
| <a id="case"></a>**Case** | A group of related alerts investigated together and closed with an outcome. | Fraud monitoring 05 |
| <a id="sar"></a>**SAR** | *Suspicious Activity Report*: a disclosure to the NCA's UK Financial Intelligence Unit about suspected money laundering. | Fraud monitoring 05 |
| <a id="nominated-officer"></a>**Nominated officer** | The person in a firm (often called the MLRO) who receives internal suspicion reports and decides whether to make a SAR. | Fraud monitoring 05 |
| <a id="tipping-off"></a>**Tipping off** | The offence of disclosing that a SAR has been made, or that an investigation is under way, where that's likely to prejudice it. | Fraud monitoring 05, Customer onboarding 03 |
| <a id="four-eyes"></a>**Four-eyes principle** | A sensitive action needs two different people: one makes it and another approves it. Also called *maker-checker*. | Fraud monitoring 05, Customer onboarding 03, Reconciliation 04 |

## Software terms

| Term | Meaning | Covered in |
|---|---|---|
| <a id="write-skew"></a>**Write skew** | A race where two transactions read overlapping data and then write *different* rows that together break a rule. Version checks on a single row don't catch it. | Card authorisation 03 |
